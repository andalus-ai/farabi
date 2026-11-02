# Query completion reconciliation contract

- **Status:** Proposed implementation contract
- **Date:** 2026-11-02
- **Authority:** ADR-009 (Proposed)
- **Scope:** Recovery after a worker cannot determine whether a completion update committed
- **Implementation status:** Design only; no production behavior or schema change is authorized by this document

## 1. Problem statement

A completion call can fail from the caller's perspective after PostgreSQL has committed the update. The worker then cannot safely infer either outcome: the transaction may have committed and the acknowledgement was lost, or the transaction may not have committed.

A subsequent database read can also fail. In that case, uncertainty remains. The worker must not translate an exception into a terminal execution state, repeat a state transition blindly, or continue provider polling based on stale in-memory state.

This contract covers the local persistence outcome of a completion transition only. It does not cover provider-side ambiguous submission reconciliation.

## 2. Required outcome model

Treat the result of a completion attempt as one of three observations:

| Observation | Meaning | Permitted response |
|---|---|---|
| Confirmed applied | Completion returned success, or a later authoritative read shows the intended terminal state and matching execution identity | Stop processing this execution in the current poll iteration |
| Confirmed not applied | A later authoritative read shows the expected pre-completion state and the same lease/fence is still current and unexpired | A controlled retry may be attempted under the existing transition contract |
| Unresolved | Completion acknowledgement failed and the authoritative read is unavailable, contradictory, or cannot establish lease/fence ownership | Defer; do not poll provider, retry completion, or invent a terminal state |

A failed read is not evidence that the completion failed. A timeout while reading is not evidence that the database rolled back the earlier update.

## 3. Reconciliation algorithm

The future worker implementation must follow this sequence:

1. **Claim once.** Acquire the existing workspace-scoped worker lease and fencing token. Do not introduce a second lease system.
2. **Observe provider state.** Poll only a known provider job ID, as the current poller does.
3. **Attempt conditional completion.** Apply the terminal transition through the existing expected-state, workspace, owner, fence, and exclusive-expiry guards.
4. **On a persistence exception, stop normal processing.** Do not treat the exception as false; it is an unknown result.
5. **Re-read authoritative state.** Use a fresh database connection and a workspace-scoped execution lookup. Read lifecycle state, lease owner, fence, and expiry together (or through a repository operation with equivalent consistency).
6. **Classify the observation.**
   - Intended terminal state, expected execution identity, and completion metadata match: record the transition as applied and stop.
   - Expected pre-transition state remains and the same lease/fence is still valid: retry only through a bounded, explicit retry policy.
   - Different terminal state, changed fence/owner, missing row, contradictory metadata, or read failure: leave the outcome unresolved and stop.
7. **Release or allow expiry of the lease safely.** Do not hold a lease while sleeping through a backoff interval. If the outcome is unresolved, do not renew merely to keep the same worker attached to it.
8. **Schedule later reconciliation.** A scheduler may retry the read using bounded exponential backoff with jitter. The schedule must be durable or reconstructible after restart; an in-memory sleep is not sufficient.
9. **Escalate unresolved records.** Once the configured age/attempt threshold is reached, expose the execution to an authorized operator workflow. Do not auto-fail or auto-resubmit it.

## 4. Poller isolation

The current QueryPollingWorker is a provider observer for executions with known provider job IDs. It is not a persistence-outcome reconciler.

Until an explicit reconciliation port and durable eligibility marker are implemented:

- an exception from complete must escape the current poll iteration or be reported as an unresolved persistence outcome;
- the same in-memory provider observation must not be replayed as though the completion definitely failed;
- the normal poller must not interpret an unknown persistence outcome as permission to poll again;
- PENDING executions without a provider job ID remain outside the normal poller;
- no new public Status value should be added solely to represent a failed local read.

The existing status enum describes execution lifecycle, not confidence in a database acknowledgement. A future model should keep outcome certainty/reconciliation state separate from provider lifecycle status.

## 5. Consistency and identity requirements

Every reconciliation read and update must be scoped by:

- execution ID;
- workspace ID;
- expected lifecycle state;
- current lease owner and fencing token, when mutating;
- lease expiry using the same exclusive boundary as the current repository (lease_expires_at > now).

A stale worker must never repair or overwrite a row using only an execution ID. A newer worker's fence always wins. A terminal row with a cleared lease is not eligible for the old worker to mutate again.

If the intended terminal state is already present but lease/fence metadata is inconsistent, classify it as unresolved for operator review rather than assuming the old worker caused the transition.

## 6. Retry and backoff policy

The production policy must be approved before implementation. At minimum it must define:

- maximum reconciliation attempts and maximum unresolved age;
- bounded exponential backoff with jitter;
- retryable database error classes versus permanent configuration/schema errors;
- how a process restart reconstructs due reconciliation work;
- operator alert thresholds and ownership;
- whether a confirmed pre-transition state permits a retry using the same logical transition.

Do not retry indefinitely at a hot loop. Do not use a retry policy to mask persistent database outages.

## 7. Observability and audit

Each attempt should eventually expose structured fields for:

- execution ID and workspace ID;
- provider/source identifier (without credentials);
- observed lifecycle state;
- intended terminal state;
- lease owner and fencing token;
- reconciliation attempt number and next eligible time;
- outcome classification: APPLIED, NOT_APPLIED, or UNRESOLVED;
- sanitized database diagnostic category;
- first unresolved time and latest reconciliation time.

Audit records must not contain SQL credentials, provider secrets, raw query results, or cross-workspace data. Operator actions must be authorized and audited.

## 8. Acceptance test matrix

| Scenario | Required assertion |
|---|---|
| Completion update rejected before commit | Persisted state remains pre-transition; no false terminal result |
| Completion committed, acknowledgement lost | Fresh read observes terminal state; no second transition |
| Completion acknowledgement lost, first read fails | Outcome remains unresolved; no retry or provider poll |
| Later read confirms terminal state | Reconciliation closes the unresolved observation without rewriting timestamps |
| Later read confirms expected state and same valid fence | Only the explicitly bounded retry path may attempt completion |
| Later read shows a newer fence | Old worker cannot mutate or renew |
| Later read shows a conflicting terminal state | No overwrite; operator-visible unresolved/conflict |
| Process restarts while unresolved | Reconciliation work remains discoverable |
| Database remains unavailable | Bounded backoff; no hot loop or fabricated lifecycle transition |
| Workspace mismatch | No state or lease metadata is disclosed or mutated |

## 9. Non-goals and rollout boundary

This contract does not introduce a schema migration, new lifecycle status, or public API; change JdbcWorkerLeaseRepository or QueryPollingWorker; define provider-side idempotency-key lookup; authorize automatic resubmission or automatic cancellation; or replace the existing V3 fenced worker lease mechanism.

Implementation should begin only after ADR-009 and the relevant policy decisions are approved. The first code change should add an explicit reconciliation port/model and persistence contract with tests; scheduler wiring and operator surfaces should follow as separate changes.
