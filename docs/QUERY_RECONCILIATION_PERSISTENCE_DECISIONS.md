# Query reconciliation persistence decisions

- **Status:** Proposed design decisions; pending ADR-009 approval
- **Date:** 2026-11-03
- **Authority:** ADR-009 (Proposed)
- **Related:** [Durable query reconciliation scheduling design](QUERY_RECONCILIATION_SCHEDULING_DESIGN.md), [Query completion reconciliation contract](QUERY_COMPLETION_RECONCILIATION_CONTRACT.md), [Query execution persistence design](QUERY_EXECUTION_PERSISTENCE_DESIGN.md)

## 1. Purpose

The scheduling design left several choices open. This document records recommended defaults so the persistence and worker implementation can be reviewed as one coherent contract.

These are recommendations, not accepted ADR decisions. No migration, runtime behavior, retry policy, or operator API is authorized by this document.

## 2. Recommended decisions

| Decision | Recommended choice | Rationale |
|---|---|---|
| Durable storage | Add reconciliation metadata to `farabi_query_executions` | One workspace-scoped row contains lifecycle, reconciliation eligibility, and execution identity; avoids a second current-work queue |
| Attempt history | Start with current-state metadata plus structured audit events; revisit append-only table if audit retention/query needs require it | Avoid premature second persistence model while retaining operational traceability |
| Initial uncertainty | Persist a reconciliation intent marker before attempting terminal completion | Makes the recovery obligation durable before an acknowledgement can be lost |
| Successful completion | Clear/resolve reconciliation marker in the same database transaction or conditional statement that applies the terminal lifecycle transition | Prevents terminal lifecycle and stale READY work from diverging |
| Lease ownership | Reuse the existing V3 lease for the execution; no second lease or independent reconciliation owner | One fence is authoritative for all execution mutations |
| Attempt count | Increment once per committed reconciliation work cycle, not per SQL statement or retry of the same transaction | Stable accounting across retries and lost acknowledgements |
| Clock | Use database time for due-work eligibility and lease predicates; retain observed timestamps for diagnostic evidence | Avoid cross-instance clock skew in claims |
| Retry | Read-only reconciliation first; only authoritative NOT_APPLIED may enter a separately approved conditional completion retry | UNRESOLVED must never be treated as safe-to-retry |
| Escalation | Persist ESCALATED scheduling state without changing execution lifecycle | Operator review must not fabricate provider or database facts |

## 3. Storage recommendation

Prefer nullable/additive columns on `farabi_query_executions` over a separate current-work table for the first implementation.

Candidate logical fields:

- `reconciliation_state`: nullable for legacy rows; values READY, IN_PROGRESS, RESOLVED, ESCALATED.
- `reconciliation_first_unresolved_at`: first durable time the current reconciliation episode was opened.
- `reconciliation_next_eligible_at`: due time for the next read-only attempt.
- `reconciliation_last_attempted_at`: time of the latest committed attempt.
- `reconciliation_attempt_count`: nonnegative count of committed work cycles.
- `reconciliation_last_outcome`: APPLIED, NOT_APPLIED, UNRESOLVED.
- `reconciliation_last_error_category`: sanitized stable category.
- `reconciliation_escalated_at` and `reconciliation_escalation_reason`: operator-review metadata.

Names and exact SQL types remain subject to migration review. Do not add these fields to the V7 proposal mechanically; reconcile them with its existing `last_reconciled_at` and `reconciliation_attempt` candidates to avoid duplicate concepts.

A separate append-only event table may be added later if the audit requirement cannot be met by existing audit infrastructure. It must not become a second source of truth for due-work eligibility.

## 4. Close the initial-enqueue crash gap with a pre-completion intent

A best-effort enqueue after a failed completion call is insufficient. The process can crash after the completion acknowledgement is lost and before it writes the enqueue record.

Recommended sequence:

1. The worker owns the current V3 lease and has observed a provider terminal state.
2. Before attempting the local terminal transition, it durably opens a reconciliation episode on the execution row, guarded by execution ID, workspace ID, current lease owner, current fence, and unexpired lease.
3. The intent records the expected pre-completion status and intended terminal status, or a durable reference to that immutable transition attempt. It also sets the first unresolved timestamp and makes the record discoverable.
4. Only after that write commits may the worker attempt the conditional terminal transition.
5. The terminal transition must atomically set the lifecycle status and mark reconciliation RESOLVED (or otherwise remove it from due-work eligibility) in the same database transaction/statement.
6. If the completion call returns an error or its acknowledgement is lost, the durable intent remains discoverable unless the completion transaction committed. A fresh authoritative read determines whether the terminal update and reconciliation resolution committed together.
7. If the process crashes after opening intent but before attempting completion, a later worker can read the expected pre-transition state and the durable intent. It must follow the approved bounded policy; it must not infer that the completion statement was executed.

The pre-completion intent is an obligation to reconcile, not evidence that the completion attempt failed. Its existence alone must never authorize a blind repeated transition.

### 4.1 Atomicity requirement

The completion lifecycle update and reconciliation resolution must share one transaction boundary. If the current repository APIs use separate connections or transactions, introduce a dedicated persistence operation that owns both updates atomically. Do not simulate atomicity with two sequential repository calls.

The pre-completion intent write is a separate committed transaction by design. It ensures that the recovery obligation exists before the uncertain completion operation begins.

### 4.2 What if the pre-completion intent write fails?

Do not call the terminal completion operation if the intent cannot be durably opened. The worker must stop processing that observation and surface a persistence failure. It must not proceed on the assumption that an in-memory marker is sufficient.

This may leave a provider-terminal execution in its pre-completion lifecycle state until a later poll/recovery cycle. That is preferable to losing the reconciliation obligation or making an untracked terminal update.

## 5. Reconciliation state and attempt semantics

Recommended state transitions:

- no active episode -> READY: pre-completion intent successfully opened;
- READY -> IN_PROGRESS: a worker begins a due attempt under the current V3 fence;
- IN_PROGRESS -> RESOLVED: authoritative APPLIED observation, or atomic successful completion;
- IN_PROGRESS -> READY: attempt ends UNRESOLVED or retryable NOT_APPLIED and policy permits another read/attempt; persist next eligible time;
- READY/IN_PROGRESS -> ESCALATED: approved attempt or age limit reached;
- RESOLVED -> no automatic transition back to READY for the same completion episode.

A later, distinct lifecycle transition must have a distinct reconciliation episode identity. Do not reset or reuse an episode in a way that allows a stale worker to resolve newer work.

**Attempt count definition:** increment once when a scheduled reconciliation work cycle is durably recorded as started. A lost acknowledgement for that same update must be reconciled by reading the persisted attempt metadata, not by incrementing again. A failed transaction that did not commit does not count. The initial pre-completion intent is not a scheduled reconciliation attempt.

Whether a NOT_APPLIED observation may perform an immediate conditional completion retry or must requeue for a new cycle remains an explicit policy decision. The initial implementation should be read-only until that policy is approved.

## 6. Lease coordination recommendation

Use the same V3 lease row and fence for provider polling and completion reconciliation. Do not create a second lease purpose or parallel lease row in the initial implementation.

- The poller may open the pre-completion intent while holding its existing lease.
- If it cannot complete or reconcile the write, it stops and does not renew the lease just to retain ownership.
- After expiry, a reconciliation worker claims the same execution through the existing V3 atomic claim path and receives a higher fence.
- Every reconciliation mutation is guarded by the new owner/fence and `lease_expires_at > database_now()`.
- Provider polling must not claim an execution while its reconciliation attempt is actively holding that same lease.
- A stale poller or reconciler cannot update lifecycle or reconciliation metadata after takeover.

This recommendation assumes the existing lease repository can claim the required lifecycle states and can be extended safely to discover reconciliation-eligible rows without broadening normal provider polling. If that assumption is false, revise the lease query/API while preserving one lease per execution and one monotonic fence.

## 7. Due-work query and indexes

Due-work discovery should select executions whose reconciliation state is READY and whose next-eligible timestamp is due according to database time. The claim must then atomically acquire the V3 lease and fence; discovery alone grants no ownership.

A partial index should be considered for READY work ordered by next eligibility. The exact index and query plan must be validated against representative PostgreSQL data and the chosen claim SQL. Do not create an index on speculative column names before the migration is approved.

The normal provider poller must continue to select only executions with known provider job IDs and eligible lifecycle states. Reconciliation discovery must not silently broaden the poller's query.

## 8. Migration compatibility

- New columns must be nullable or have safe defaults for existing V1–V6 rows.
- Legacy rows must not be automatically marked READY based only on PENDING, SUBMITTED, or RUNNING status.
- Only new execution flows using the pre-completion intent contract may open a reconciliation episode initially.
- No backfill may assert that a historical completion was ambiguous without durable evidence.
- If a dedicated event history is later introduced, it must be written transactionally with the current-state row update.

## 9. Required tests

PostgreSQL integration tests must prove:

1. Intent is committed before the terminal completion operation is invoked.
2. Intent write failure prevents the terminal completion operation.
3. Crash/restart after intent but before completion leaves discoverable READY work.
4. Successful completion and reconciliation resolution commit atomically.
5. Lost completion acknowledgement followed by a fresh read observes either both changes committed or neither.
6. Lost acknowledgement while the follow-up read is unavailable leaves durable due work.
7. Attempt-start acknowledgement loss does not double-increment attempt count.
8. Lease takeover increments the existing V3 fence and stale workers cannot mutate either lifecycle or reconciliation fields.
9. Provider poller and reconciler cannot concurrently own the same execution.
10. Exact lease expiry remains exclusive.
11. Workspace mismatch reveals no reconciliation metadata.
12. Legacy rows remain readable and are not auto-enqueued.
13. Repeated resolution is idempotent and does not rewrite lifecycle timestamps.
14. ESCALATED does not alter execution status or enable resubmission.

Unit tests should additionally cover the reconciliation state machine, attempt accounting, policy boundaries, and classification-to-scheduling decisions.

## 10. Decisions still requiring approval

1. Confirm execution-row storage rather than a dedicated current-work table.
2. Confirm the pre-completion intent protocol and its additional write before every terminal completion.
3. Confirm atomic completion + reconciliation resolution repository operation.
4. Confirm the exact expected/intended transition fields or immutable transition-attempt identity.
5. Confirm that one V3 lease per execution is sufficient for provider polling and reconciliation.
6. Approve attempt/age/backoff/escalation values and whether NOT_APPLIED can trigger immediate retry.
7. Decide whether existing audit infrastructure is sufficient or append-only attempt history is required.
8. Approve migration naming, types, indexes, and rollout feature flags.

Until these decisions are approved, this document is a recommendation for review, not implementation authorization.
