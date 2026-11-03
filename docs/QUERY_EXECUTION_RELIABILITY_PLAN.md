# Query execution reliability: implementation plan

- **Status:** Planning
- **Date:** 2026-11-01
- **Design authority:** Proposed ADR-009
- **Persistence detail:** [Additive persistence design](QUERY_EXECUTION_PERSISTENCE_DESIGN.md)
- **Scope:** Engine contracts, persistence adapters, provider adapters, recovery workers, and Quarkus wiring
- **Non-goal:** This plan does not authorize production behavior changes. Implement each phase only after ADR-009 and its policy defaults are approved.

## Current implementation baseline

The current engine deliberately persists a PENDING execution before invoking the provider. A thrown submission call therefore leaves a durable record and does not trigger an automatic retry. This is the correct conservative baseline for an ambiguous remote outcome.

The present contract is smaller than the reliability model proposed in ADR-009:

- `QueryContracts.Execution` contains identity, workspace/subject/source, provider job ID, status, and timestamps only.
- `Status` is limited to PENDING, SUBMITTED, RUNNING, SUCCEEDED, FAILED, and CANCELLED.
- `ExecutionRepository` supports create, workspace-scoped find, and status transition with an optional provider job ID; it has no compare-and-set version or reconciliation update operation. Worker leasing is a separate `WorkerLeases.Repository` port, with a JDBC implementation backed by the existing V3 lease/fencing columns; do not duplicate that mechanism.
- `QueryPorts.Executor` accepts a compiled plan and returns a provider job ID; it has no idempotency key, deadline budget, or capability declaration.
- `QueryLifecycle.Provider` supports status, results, and cancel by provider job ID; it cannot look up an ambiguous submission by correlation key or describe unsupported capabilities.
- `QueryLifecycleService.cancel` sends a provider cancellation request but does not persist a cancellation-requested event/marker.
- `DurableQueryService` leaves an ambiguous submit PENDING and documents manual reconciliation. It has no deadline policy or ambiguous-submission reconciliation by idempotency/correlation key.
- `QueryPollingWorker` is already present: it claims a fenced worker lease, loads the workspace-scoped execution, and polls provider status only when a provider job ID is known. It can advance known SUBMITTED/RUNNING executions to observed provider states. It is not an ambiguous-submission reconciler: a PENDING execution without a provider job ID is rejected before any provider call.
- Results are gated on local SUCCEEDED status, and page limits are independently enforced.

The first schema/API change must therefore be additive and coordinated. Do not add a TIMED_OUT terminal status or treat local timeout as proof of remote termination before the remote-outcome model exists.

## Delivery sequence

### Phase 0 — Approve policy and compatibility decisions

Before production changes, record decisions for:

1. Maximum execution duration and whether callers may request a shorter duration.
2. Whether the deadline starts before compilation, immediately before provider submission, or at request acceptance. Prefer one explicitly defined end-to-end budget; compilation must consume the same budget if included.
3. Default behavior for providers without idempotency, lookup, cancellation, or deadline support.
4. Retention and operator escalation policy for unresolved remote outcomes.
5. Whether a new public lifecycle status is needed, or whether command state and remote outcome certainty remain separate fields.
6. Backward compatibility for existing persisted rows and API clients.

**Exit criteria:** ADR-009 is accepted, policy defaults are documented, and the persistence/API compatibility strategy is approved.

### Phase 1 — Introduce an additive execution metadata model

Evolve the engine-owned execution record without changing existing status semantics initially.

Proposed conceptual fields:

| Field | Purpose |
|---|---|
| `deadlineAt` | Absolute UTC deadline persisted before remote submission |
| `timeoutPolicyVersion` | Identifies the server-controlled duration policy |
| `idempotencyKey` | Stable Farabi-generated submission identity |
| `outcomeCertainty` | Distinguishes confirmed state from unresolved/unknown remote outcome |
| `cancelRequestedAt`, `cancelRequestedBy` | Durable cancellation intent |
| `lastReconciledAt`, `reconciliationAttempt` | Recovery progress and observability |
| `version` | Optimistic concurrency/fencing support for state updates |

The exact Java representation may be an immutable metadata value object to avoid an unmanageable record constructor. Keep transient command state (for example, cancellation requested) distinct from provider terminal status.

Persistence migration requirements:

- Add nullable columns or a versioned metadata representation first.
- Backfill existing rows conservatively: do not invent deadlines, idempotency keys, or provider certainty that cannot be derived.
- Keep old readers operational during a rolling deployment.
- Make new writes populate required metadata before enabling any new worker.
- Add workspace-scoped unique constraints only where the intended key semantics are proven.
- Verify indexes for deadline scans and unresolved-outcome reconciliation.
- Document rollback behavior; a rollback must not silently discard unresolved remote-job evidence.

**Exit criteria:** Existing records remain readable; new records can persist and round-trip all approved metadata; migration and rollback tests pass.

### Phase 2 — Version the provider execution contract

Introduce a provider-neutral submission request containing the compiled plan, stable idempotency/correlation key, and remaining deadline budget. Do not pass arbitrary caller-controlled provider options through the engine.

Define explicit provider capabilities, at minimum:

- supports idempotent submission;
- supports lookup by idempotency/correlation key;
- supports cancellation;
- supports execution deadlines or query timeouts;
- supports authoritative terminal-state observation.

Capability absence must be explicit. The engine must not emulate unsupported guarantees.

Provider submission should return a structured result that can distinguish accepted-with-job-ID from a provider contract that only acknowledges acceptance. Preserve compatibility through an adapter layer or versioned port while existing adapters migrate.

**Exit criteria:** Contract tests prove that each adapter declares capabilities truthfully, passes stable keys when supported, and never claims remote termination from a local timeout.

### Phase 3 — Make submission idempotent and ambiguity explicit

Submission sequence after approval:

1. Authorize and compile under the selected deadline budget.
2. Create the durable execution with deadline and stable idempotency key.
3. Submit exactly once using that key.
4. Persist the provider job ID and accepted state with compare-and-set semantics.
5. If the call fails ambiguously, mark the remote outcome unresolved; do not blindly submit again.
6. Reconcile by provider job ID, or by idempotency key where supported.
7. If lookup is unsupported, retain the unresolved record and surface it for operator action.

The idempotency key must be generated by Farabi, persisted before the network call, scoped to the execution, and reused on all safe retries/reconciliation. Never derive it from untrusted prompt text.

**Exit criteria:** Fault-injection tests cover lost responses before and after provider acceptance, duplicate delivery, process restart, and concurrent submission attempts.

### Phase 4 — Add deadline enforcement and cancellation intent

- Resolve and validate one absolute deadline before persisting the execution.
- Use UTC instants for durable deadlines and a monotonic clock for in-process elapsed-time calculations.
- Pass only remaining budget downstream; no adapter may extend it.
- For dashboard fan-out, share one overall deadline across all chart queries.
- Persist cancellation intent before calling the provider.
- Make repeated cancellation requests idempotent.
- Treat provider cancellation acknowledgement as an acknowledgement, not a terminal state.
- When a deadline expires, request cancellation only when the provider job is known and cancellation is supported.
- Keep the remote outcome unresolved until a provider terminal state is observed.

**Exit criteria:** Tests prove deadlines survive restarts, no new chart work is scheduled after expiry, cancellation intent is durable, and local timeout never fabricates CANCELLED or TIMED_OUT.

### Phase 5 — Reconciliation worker and fencing

Extend the existing `QueryPollingWorker` into a bounded, observable reconciliation worker that:

- claims eligible unresolved executions using a lease and monotonically increasing fencing/version token;
- queries by provider job ID or idempotency key according to declared capabilities;
- records every observation and attempt;
- applies transitions only if the worker still owns the current fence;
- backs off with bounded jitter and a maximum attempt policy;
- stops automatic retries when the provider cannot establish the outcome;
- exposes unresolved age, attempt count, provider, workspace, and last diagnostic to operators.

A worker lease expiring must not allow a stale worker to overwrite a newer result. Provider success/failure observed after a cancellation race takes precedence over an unconfirmed cancellation request.

**Exit criteria:** Multi-worker race tests prove stale workers cannot mutate newer state; unresolved providers are visible and never silently discarded.

### Phase 6 — API, audit, and operational rollout

- Keep result retrieval gated on confirmed SUCCEEDED.
- Preserve existing workspace and subject authorization on every lifecycle and operator endpoint.
- Expose deadline, cancellation-request, and unresolved-outcome information without leaking provider secrets or cross-workspace identifiers.
- Audit submission, acceptance, ambiguity, deadline expiry, cancellation request/acknowledgement, reconciliation observations, operator intervention, and terminal transitions.
- Add metrics for overdue jobs, unresolved age, reconciliation lag, cancellation latency, and provider capability coverage.
- Roll out behind configuration/feature flags: metadata persistence first, then provider capabilities, then reconciliation in observe-only mode, then controlled cancellation/deadline enforcement.
- Define a kill switch that stops new automated reconciliation/cancellation without erasing durable evidence.

**Exit criteria:** Security review, migration rehearsal, provider contract suite, operational runbook, and staged rollout checks are complete.

## Required test matrix

| Area | Required cases |
|---|---|
| Persistence | Metadata round-trip; legacy-row read; migration rollback; deadline indexed scan |
| Submission | Persist-before-call; stable key; duplicate request; lost response; accepted-but-response-lost |
| Provider capabilities | Each capability supported/unsupported; lookup by key; no false guarantees |
| Deadline | Expired-before-submit; compilation consumes budget; deadline unchanged after restart; shared dashboard deadline |
| Cancellation | Persist-before-dispatch; repeated request; provider ack without terminal state; success racing cancellation |
| Reconciliation | Lookup by job ID/key; unresolved provider; retry backoff; operator escalation; restart |
| Concurrency | Lease expiry; stale fencing token; competing reconcilers; compare-and-set transition conflict |
| Results | No results before confirmed success; row/page caps remain independent |
| Security | Workspace/subject isolation; operator authorization; no provider secret leakage |
| Observability | Audit completeness; metrics; unresolved-job alert and runbook |

## Change boundaries

- This document and ADR-009 are design artifacts only.
- Do not change production execution semantics, persistence schema, public API, provider adapters, or default timeout policy as part of this planning step.
- Keep test-only dependency additions in test scope.
- Implement phases as reviewable, independently deployable changes with migration and rollback notes.
- Do not open or merge a pull request without explicit direction.
