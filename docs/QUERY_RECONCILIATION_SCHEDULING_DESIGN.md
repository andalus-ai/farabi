# Durable query reconciliation scheduling design

- **Status:** Proposed design; not approved for production implementation
- **Date:** 2026-11-03
- **Authority:** ADR-009 (Proposed)
- **Related contracts:** [Query completion reconciliation contract](QUERY_COMPLETION_RECONCILIATION_CONTRACT.md), [Query execution persistence design](QUERY_EXECUTION_PERSISTENCE_DESIGN.md)
- **Scope:** Durable discovery, scheduling, claiming, retry budgeting, and escalation for unresolved local completion outcomes

## 1. Purpose and boundary

The engine now has a read-only completion reconciliation model, reader port, JDBC reader, and coordinator. These classify an authoritative observation as APPLIED, NOT_APPLIED, or UNRESOLVED. They do not persist eligibility, schedule future work, claim work, retry completion, or change the provider poller.

This document defines the next design boundary: how unresolved completion observations remain discoverable across process restarts and are safely worked by multiple instances.

This is a design proposal only. ADR-009 remains Proposed. Do not add a migration, scheduler, worker, automatic retry, operator endpoint, or production behavior until ADR-009 and the policy decisions in this document are approved.

This design is specifically for **local persistence completion uncertainty**. It does not solve ambiguous provider submission, provider status lookup by idempotency key, cancellation reconciliation, or provider deadline semantics. Those remain separate contracts.

## 2. Design principles

1. **Durable before deferred.** A worker must persist reconciliation eligibility before it abandons an unresolved completion outcome.
2. **Recoverable after restart.** Due work is reconstructed from durable state, never only from an in-memory queue, timer, or exception.
3. **One lease system.** Reuse the existing V3 workspace-scoped worker lease fields and monotonically increasing fencing token. Do not introduce a second lease table or independent lock protocol.
4. **Separate lifecycle from certainty.** Reconciliation metadata must not overload the execution Status enum or rewrite provider lifecycle facts.
5. **Read before retry.** Only an authoritative NOT_APPLIED classification can become a completion retry candidate. UNRESOLVED is never permission to retry.
6. **Fencing at every mutation.** Every claim, attempt update, retry, and close operation must be guarded by execution ID, workspace ID, lease owner, fence, and the established exclusive expiry rule.
7. **Bounded and observable.** Attempts and unresolved age are bounded by approved policy, with explicit operator escalation rather than endless retries or fabricated terminal states.
8. **Poller isolation.** Provider polling and persistence reconciliation are separate work classes, even if they share execution records and lease infrastructure.

## 3. Durable reconciliation record

The durable record must represent work eligibility and scheduling independently of the execution lifecycle status. Two storage shapes remain viable and require a decision before migration:

- **Execution-row extension:** add nullable reconciliation fields to `farabi_query_executions`. This is simpler to query and atomically update with execution lifecycle state, but can make the execution row carry a growing set of operational concerns.
- **Dedicated reconciliation work/history table:** store one current work item per execution, optionally with append-only attempt history. This separates scheduling from lifecycle and preserves diagnostics, but introduces additional transactional and retention requirements.

Do not create both a current-work table and an independent lease table. The existing V3 lease is the sole concurrency/fencing mechanism.

Regardless of storage shape, the logical record needs these fields:

| Logical field | Meaning |
|---|---|
| execution_id, workspace_id | Composite scoped identity; both required on every lookup and mutation |
| reconciliation_state | READY, IN_PROGRESS, RESOLVED, or ESCALATED; scheduling state, not execution lifecycle |
| first_unresolved_at | Immutable timestamp for the current unresolved episode |
| last_attempted_at | Timestamp of the most recent reconciliation attempt |
| next_eligible_at | Durable due time used by restart-safe discovery |
| attempt_count | Monotonically increasing count of actual reconciliation attempts |
| last_outcome | APPLIED, NOT_APPLIED, or UNRESOLVED |
| last_error_category | Sanitized stable category; never raw credentials, SQL, or provider payload |
| last_observed_status | Lifecycle status observed by the reconciler, where available |
| last_observed_fence | Fence observed during the latest authoritative read |
| escalated_at, escalation_reason | Set when policy requires operator review |
| created_at, updated_at | Persistence timestamps |

The exact columns, constraints, table shape, and whether attempt history is append-only remain open. The logical contract must not be weakened by a particular storage choice.

### 3.1 State semantics

- **READY:** durable work exists and may be claimed when due.
- **IN_PROGRESS:** a worker has claimed the existing V3 lease and is performing one bounded attempt. This state must not itself be treated as proof of a valid lease; the V3 owner/fence/expiry are authoritative.
- **RESOLVED:** the authoritative read classified the intended completion as APPLIED. No further completion reconciliation is due.
- **ESCALATED:** automated attempts are exhausted or unresolved age exceeds policy. Work is visible for authorized operator review and is not automatically retried.

A NOT_APPLIED observation is a retry candidate, not a separate durable terminal state. It may be requeued only under the approved bounded retry policy and while the original completion attempt remains valid for the same execution/fence semantics.

An UNRESOLVED observation remains eligible for a later read, subject to backoff and limits. It must never be converted to NOT_APPLIED merely because a database read failed.

## 4. Durable discovery and restart recovery

A reconciliation worker must discover due work with a database query equivalent to:

- workspace-scoped records in READY state;
- `next_eligible_at <= database_now`;
- not ESCALATED or RESOLVED;
- corresponding execution still exists in the same workspace.

The actual SQL and index design are deferred until storage shape is approved.

On process startup, every worker instance may scan for due records. Duplicate discovery is acceptable; duplicate ownership is not. The subsequent claim must be atomic and must acquire the existing V3 fenced lease. A process-local queue may be used only as a performance hint, never as the source of truth.

Do not depend on an in-memory delay, scheduled future, or retry callback surviving a process restart. If the process crashes after persisting eligibility but before scheduling, the next scan must rediscover the record. If it crashes after claim, lease expiry must make the work claimable by another worker under the existing V3 rules.

A periodic scan interval is an operational configuration decision. It must not be confused with the retry backoff interval.

## 5. Claiming, lease reuse, and fencing

The existing `farabi_worker_leases` V3 mechanism remains the only lease protocol:

- claim is workspace-scoped and atomic;
- a successful takeover increments the monotonic fence;
- a lease is valid only while `lease_expires_at > now`;
- equality with the expiry instant means expired;
- renew and completion require matching workspace, owner, and fence;
- a stale worker cannot update execution or reconciliation metadata after a newer fence is issued.

The implementation must decide whether reconciliation claims share the same execution lease namespace with provider polling or use a distinct lease purpose represented in the existing lease model. It must not allow the poller and reconciler to concurrently mutate the same execution under separate leases. Any lease-purpose extension must preserve one authoritative owner/fence per execution and be compatible with existing V3 rows.

The claim operation should atomically establish ownership and move due work into its in-progress scheduling state. If either update cannot be made atomically with the lease claim, define a recoverable transaction protocol before implementation; do not accept a window in which a record is permanently IN_PROGRESS without an expiring V3 lease.

Every metadata mutation after claim must include execution ID, workspace ID, lease owner, fence, and an unexpired lease predicate. A stale worker may record diagnostics only through a separately authorized, non-mutating telemetry channel; it may not write scheduling or lifecycle state.

## 6. Attempt algorithm

One scheduled attempt performs a single bounded authoritative read through the reconciliation reader:

1. Claim due work using the existing V3 lease/fence.
2. Load the attempt identity and expected completion transition.
3. Read lifecycle and lease metadata together through the workspace-scoped reader.
4. Classify using the existing engine model.
5. Apply exactly one outcome path:
   - **APPLIED:** atomically mark reconciliation RESOLVED. Do not rewrite terminal status, completion timestamp, or provider observation.
   - **NOT_APPLIED:** mark a retry candidate only if the same expected state and same unexpired owner/fence are still authoritative. Requeue for bounded retry or perform a separately specified conditional completion operation; never turn this into an unconditional status update.
   - **UNRESOLVED:** persist sanitized diagnostic category and a future eligibility time, or escalate if the attempt/age budget is exhausted. Do not retry completion and do not call the provider poller based on this result.
6. Release the lease through the existing fenced completion/release contract, or allow it to expire if safe release cannot be confirmed. Do not sleep while holding a lease.

The coordinator currently performs a read-only classification. It must remain read-only. Retry execution and scheduling belong to a future orchestration component with explicit ports and transaction boundaries; do not add side effects to `CompletionReconciler`.

If the reconciliation metadata write itself has an ambiguous acknowledgement, apply the same principle: stop, re-read durable scheduling and lease state, and classify the persistence result. Do not blindly increment attempt_count twice.

## 7. Retry policy and escalation

Policy values must be approved and configurable before implementation. This document intentionally does not prescribe numeric defaults.

The policy must define:

- maximum attempt count;
- maximum unresolved age;
- initial backoff, maximum backoff, and jitter strategy;
- which database error categories are retryable;
- whether a NOT_APPLIED observation permits one immediate conditional retry or only a later scheduled attempt;
- maximum work per scan/batch and per workspace;
- escalation notification threshold and owner;
- retention period for resolved and escalated records.

Backoff should be bounded exponential with jitter, computed from durable attempt metadata and persisted as `next_eligible_at`. Use a single authoritative clock source for due-time comparison, preferably database time for claim queries. Do not use local process time to decide whether another instance's durable work is due.

When attempt count or unresolved age reaches its approved limit, atomically transition the scheduling record to ESCALATED and preserve the last observed evidence. Escalation must not change execution lifecycle status, trigger resubmission, or mark the query failed.

Operator resolution requires an authorized, workspace-scoped action with an explicit reason and audit event. Permitted operator actions (for example, confirm resolved, requeue read-only reconciliation, or close as manually reviewed) must be specified in a separate operator contract. No operator action may bypass provider or execution authorization.

## 8. Transactional and concurrency requirements

The future persistence API should expose intent-specific operations rather than a generic update with many nullable fields. Candidate operations include:

- enqueue unresolved completion work idempotently;
- claim due work and acquire/renew the V3 lease;
- record an attempt observation under the current fence;
- requeue with a computed next eligible time;
- mark resolved under the current fence;
- mark escalated under the current fence;
- read current work and lease state for acknowledgement reconciliation.

Required invariants:

1. At most one active lease owner/fence exists per execution.
2. Enqueue is idempotent for the same unresolved completion episode.
3. Attempt count increases at most once for a committed attempt record.
4. A worker with a stale fence cannot requeue, resolve, escalate, renew, or complete.
5. A resolved record cannot be reopened by a stale attempt.
6. A workspace mismatch behaves as missing and reveals no cross-workspace metadata.
7. An expired lease cannot be renewed or completed, including at the exact expiry instant.
8. Crash between claim and observation is recoverable by lease expiry.
9. Crash after a committed outcome but before acknowledgement is recoverable by a fresh read without duplicating attempt count or changing terminal timestamps.
10. No transaction is held open across a network/provider call or a backoff wait.

PostgreSQL integration tests are required for claim races, lease takeover, exact-expiry behavior, idempotent enqueue, transactional updates, and lost acknowledgements. H2 tests may supplement but cannot substitute for PostgreSQL concurrency evidence.

## 9. Failure matrix

| Failure point | Required behavior |
|---|---|
| Process crashes before durable enqueue | Caller must not silently discard uncertainty; enqueue must be part of the completion-failure handling contract or be reconstructible from durable execution metadata |
| Process crashes after enqueue, before claim | Startup/periodic scan rediscovers due READY work |
| Two workers discover same item | Atomic V3 claim allows one current owner/fence |
| Worker crashes while IN_PROGRESS | Lease expires; another worker claims with a higher fence |
| Reader returns unavailable or throws | UNRESOLVED; bounded backoff; no completion retry |
| Reader observes newer fence | Old worker cannot mutate; current owner is authoritative |
| Attempt update commits but acknowledgement is lost | Fresh read confirms persisted attempt/state; do not increment again |
| Database remains unavailable | Backoff and eventual escalation; no hot loop |
| Workspace ID does not match | Treat as missing; no disclosure or mutation |
| Attempt or age budget exhausted | ESCALATED; execution lifecycle remains unchanged |
| Resolved record is encountered again | Idempotent no-op; terminal timestamps remain unchanged |

A critical open design issue is the first row: if uncertainty is known only in process memory and the process dies before enqueue, the record cannot be rediscovered unless the execution row already contains a durable reconciliation marker or the completion update protocol atomically writes one. The final storage design must close this gap. A best-effort enqueue after an exception is not sufficient by itself.

## 10. Observability and audit

Emit structured metrics/logs for:

- due, claimed, resolved, escalated, and overdue item counts;
- attempt count and unresolved age distributions;
- claim conflicts and lease takeovers;
- APPLIED, NOT_APPLIED, and UNRESOLVED classifications;
- database error category and retry scheduling delay;
- workspace-scoped backlog age, without exposing cross-workspace data;
- operator action count and outcomes.

Audit durable state transitions and operator actions with execution ID, workspace ID, actor/system identity, attempt number, observed fence, classification, and sanitized reason. Never log SQL credentials, provider secrets, raw query results, or sensitive query text.

Metrics should support alerts for oldest unresolved age, sustained backlog growth, repeated database read failures, and escalation volume. Alert thresholds are operational policy and remain TBD.

## 11. Rollout sequence

1. Approve ADR-009 and decide the open policy and storage questions below.
2. Select execution-row extension or dedicated reconciliation work/history storage; define how initial uncertainty is durably recorded atomically.
3. Define the lease-purpose interaction between provider polling and reconciliation while retaining one V3 fenced lease.
4. Add additive migration and PostgreSQL migration/concurrency tests; preserve legacy NULL semantics.
5. Add intent-specific engine ports and JDBC operations, initially behind a disabled feature flag.
6. Add durable scanner/worker with read-only reconciliation only; verify restart recovery, fencing, and escalation without automatic completion retry.
7. Add a separately reviewed conditional retry path only for authoritative NOT_APPLIED observations.
8. Add operator review surfaces and audit before enabling automated escalation notifications.
9. Enable gradually with backlog, age, error, and cross-workspace isolation monitoring; document rollback behavior.

No step should silently convert legacy PENDING or SUBMITTED rows into reconciliation work without an approved evidence-based classification rule.

## 12. Open decisions required before implementation

1. **Storage shape:** execution-row extension or dedicated current-work table; is append-only attempt history required?
2. **Atomic initial enqueue:** how is unresolved completion uncertainty recorded in the same durable transaction boundary as the completion attempt so a crash cannot lose it?
3. **Lease purpose:** how does reconciliation coordinate with provider polling while retaining one lease/fence per execution?
4. **Attempt semantics:** does attempt_count count each authoritative read, each retryable completion attempt, or each scheduled work cycle? The chosen definition must be consistent and idempotent.
5. **Clock authority:** database time or application-supplied observedAt for due-time and age decisions; how are clock skew and test clocks handled?
6. **Policy values:** maximum attempts, maximum age, backoff bounds, jitter, scan cadence, batch size, and per-workspace fairness.
7. **Retry semantics:** whether NOT_APPLIED permits immediate bounded completion retry or only requeue for a new worker cycle.
8. **Escalation ownership:** operator role, alert channel, required reason, and allowed resolution actions.
9. **Retention:** how long resolved/escalated records and attempt history remain available for audit.
10. **Provider ambiguity boundary:** confirm that provider-side submission/status/cancellation reconciliation remains a separate work queue and contract.

## 13. Acceptance criteria

The design is ready for implementation only when:

- uncertainty cannot be lost between process failure and durable enqueue;
- due work is discoverable after restart without in-memory state;
- concurrent workers are serialized by the existing V3 fenced lease;
- stale fences cannot mutate scheduling or execution state;
- lease expiry remains exclusive at the exact expiry instant;
- UNRESOLVED never triggers completion retry;
- APPLIED closes work without rewriting lifecycle timestamps;
- NOT_APPLIED is retried only under an approved bounded conditional path;
- attempt count and unresolved age are durable and enforceable;
- exhausted work escalates without changing execution lifecycle;
- workspace isolation, lost acknowledgements, crash recovery, and PostgreSQL concurrency are covered by integration tests;
- provider polling remains isolated from local persistence reconciliation.

Until these criteria and the open decisions are approved, this document authorizes design discussion only—not a schema migration or production implementation.
