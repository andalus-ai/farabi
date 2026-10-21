# R3 — Query lifecycle and provider integration

## Scope
- Engine-owned provider lifecycle port: status, result pages and cancellation.
- Workspace/subject-checked application service for status, results and cancellation.
- Bounded query polling worker using PostgreSQL fencing primitives from R2.
- Dremio adapter for job status, paged results and cancellation.

## State transitions
- SUBMITTED -> RUNNING when remote state is RUNNING.
- RUNNING -> SUCCEEDED, FAILED or CANCELLED on terminal remote states.
- If remote completion occurs before the first poll, the worker transitions SUBMITTED -> RUNNING, then reacquires a new fence and completes the terminal transition.
- PENDING is never automatically retried because remote submission might already have been accepted.
- Cancellation request does not optimistically set local CANCELLED; worker reconciliation observes provider terminal state.

## Trust boundary
Provider job IDs are read only from workspace-scoped execution records. User-supplied job IDs are never accepted by the application service. Subject ownership is checked before provider access.

## Remaining gaps
No scheduler/discovery of pending jobs, real authenticated Quarkus query API, Dremio integration test environment, production result caching, quotas, cancellation retry policies or telemetry. Remote provider errors propagate; scheduler retry/backoff remains a future concern. Database migrations V1–V3 must be applied.
