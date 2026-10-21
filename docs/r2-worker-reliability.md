# R2: workspace-scoped repositories and fenced worker primitives

## Delivered
- Execution and dashboard artifact repository lookups now require workspace IDs; JDBC SQL includes workspace filters.
- Execution state transitions include workspace IDs and compare-and-set expected status.
- PostgreSQL worker lease claims are atomic UPDATE/RETURNING operations, incrementing a monotonically increasing fence.
- Renewals and completion require current owner, fence, unexpired lease, workspace and expected status.
- Migration V3 adds lease owner, fence and expiration; apply V1, V2, V3 in order.
- WorkerCoordinator validates TTL and allowed worker transitions.

## Reliability model
Only SUBMITTED/RUNNING jobs are leaseable. PENDING submissions are intentionally excluded because an ambiguous provider submission must not be replayed. An expired lease may be claimed by a new worker, which increments the fence. A stale worker cannot renew or complete once the fence changes.

## Explicitly not delivered
No scheduled worker, Dremio status polling, provider idempotency, result paging/cancellation, audit pipeline, trusted identity binding or live PostgreSQL integration tests. Runtime remains fail-closed without public query execution routes.

## Breaking internal API
Custom implementations of QueryPorts.ExecutionRepository and DashboardArtifacts.Repository must add workspace-scoped lookup arguments. This is a pre-stable API correction.
