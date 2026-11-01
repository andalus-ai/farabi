# ADR-009: Query deadlines, cancellation, and ambiguous provider outcomes

- **Status:** Proposed
- **Date:** 2026-11-01
- **Scope:** Query submission and lifecycle across the Farabi engine and provider adapters
- **Depends on:** ADR-004 (persist intent before remote submission), ADR-007 (fenced workers and idempotent recovery)

## Context

Farabi persists a PENDING execution before calling a remote provider. If the provider accepts the query but the response is lost, the caller sees an exception while the remote job may still be running. Retrying blindly can create duplicate work. Cancellation is asynchronous, and a successful cancellation request does not prove that the provider stopped the query.

The current lifecycle has PENDING, SUBMITTED, RUNNING, SUCCEEDED, FAILED, and CANCELLED. It has no durable deadline, no explicit cancellation-requested state, and no first-class representation of an unknown remote outcome. A local timeout therefore cannot safely be treated as provider-confirmed termination.

## Decision (proposed)

### 1. One absolute deadline per execution

- Resolve the effective execution deadline before persisting the execution.
- Persist an absolute UTC deadline (deadlineAt) with the execution record; do not rely on an in-memory timer.
- Apply a server-controlled maximum duration and reject invalid or already-expired deadlines before provider submission.
- Propagate the remaining budget to compilation and provider adapters where supported. Adapters must not extend the caller's deadline.
- Use a monotonic clock for elapsed-time measurement inside a process; use the persisted UTC deadline for recovery across process restarts.
- Dashboard preview fan-out must share one overall deadline rather than granting every chart a fresh full timeout.

### 2. Separate local deadline expiry from remote terminal state

- Reaching deadlineAt means Farabi's caller budget expired. It does not prove that the provider stopped executing.
- Record the local deadline event and request provider cancellation when a known remote job ID exists.
- Keep the remote outcome as unresolved until reconciliation observes a provider terminal state.
- Introduce an explicit outcome such as OUTCOME_UNKNOWN (or an equivalent durable reconciliation marker) for accepted/possibly accepted work whose provider state cannot yet be established.
- A TIMED_OUT terminal status is permitted only when the provider contract confirms that execution is terminal due to timeout, or when Farabi can prove the request was never accepted. Never infer it solely from a client-side socket timeout.

### 3. Cancellation is a request, not a terminal fact

- Persist a cancellation-requested timestamp/actor before dispatching the cancellation command.
- Make cancellation idempotent; repeated requests must not create additional provider jobs or contradictory local transitions.
- Treat provider acknowledgement as acknowledgement of the request only.
- Transition to CANCELLED only after provider reconciliation confirms cancellation (or confirms that the job never started).
- Preserve provider-reported success/failure if the job completed before cancellation took effect.

### 4. Idempotent submission and reconciliation

- Generate and persist a stable Farabi execution/idempotency key before the first provider call.
- Pass that key as the provider idempotency token or correlation key when the adapter supports it.
- On ambiguous submission failure, do not automatically submit again unless the provider guarantees idempotent acceptance for that key.
- Reconciliation must query by provider job ID when known, otherwise by the stable idempotency/correlation key where supported.
- If the provider cannot look up an ambiguous submission, retain an explicit unresolved state for operator intervention; do not silently mark it failed or retry it.
- Recovery workers must use leases/fencing tokens so stale workers cannot overwrite newer reconciled state.

### 5. Results and resource controls

- Results remain unavailable until the provider outcome is confirmed SUCCEEDED.
- Enforce row/page limits independently of execution deadlines.
- Provider adapters should expose cancellation capability and deadline semantics explicitly; unsupported capabilities must be reported rather than simulated.
- Audit submission, deadline expiry, cancellation request/acknowledgement, reconciliation observations, and terminal transitions with workspace, subject, execution ID, provider, and correlation key.

## Required contract additions

The implementation should evolve the execution contract to carry at least:

- deadlineAt and the effective timeout policy/version;
- stable idempotencyKey / correlation key;
- cancellation-request metadata;
- last reconciliation time and attempt metadata;
- a distinct remote-outcome certainty marker.

The exact enum and persistence migration should be designed alongside the provider adapter contract. Avoid overloading the existing terminal Status enum with transient command states.

## Acceptance tests

1. Deadline is persisted before the first provider call and is unchanged across retries/recovery.
2. An already-expired deadline prevents provider submission.
3. A lost submission response leaves an unresolved execution and never triggers a blind second submission.
4. Reconciliation can discover an accepted job by idempotency key and attach its provider job ID.
5. A local deadline expiry requests cancellation but does not mark the job CANCELLED or provider-TIMED_OUT without confirmation.
6. Cancellation request is idempotent; repeated calls do not duplicate remote work.
7. A provider success racing with cancellation remains SUCCEEDED if that is the confirmed terminal outcome.
8. Stale reconciliation workers cannot overwrite state after losing their fencing token.
9. Results are unavailable for unresolved, cancellation-requested, failed, cancelled, or timed-out executions.
10. Dashboard chart fan-out respects one shared deadline and stops scheduling new chart work after expiry.

## Consequences

This design favors correctness over optimistic retry. Ambiguous jobs may remain unresolved and require provider lookup or operator action, but Farabi will not claim that work stopped when it may still be consuming resources. Provider adapters with idempotency lookup, cancellation, and status APIs can recover automatically; weaker providers require an explicit degraded/reconciliation path.

## Implementation boundary

This ADR is a proposed design, not an implementation authorization. The current code continues to use its existing status model and behavior until the contract, provider capabilities, timeout defaults, and persistence migration are approved and implemented in a separate change.
