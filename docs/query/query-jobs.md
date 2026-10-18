# Query job lifecycle — P2

This slice introduces a durable job metadata schema, a JDBC job store, and an owner-aware application service.

A job belongs to a workspace, subject, and source. A random UUID identifies the BI job; an optional provider job ID identifies the downstream engine job. The service checks workspace membership and exact subject ownership before reads and state changes. Status transitions use an atomic compare-and-swap update, and expired jobs cannot be transitioned.

## Lifecycle

`PENDING -> SUBMITTED -> RUNNING -> SUCCEEDED|FAILED|CANCELLED`.

`PENDING` can also transition directly to `FAILED` or `CANCELLED`; `SUBMITTED` can transition to `FAILED` or `CANCELLED`. Terminal states cannot transition.

## Integration still required

This milestone does **not** yet connect `QueryJobService` to `GovernedDremioQueryService` or implement job status polling, paginated results, cancellation against Dremio, or HTTP endpoints. Provider job IDs and transitions must be written only by trusted server workers, not untrusted callers. Before production, split user-visible read methods from privileged worker transitions, add audit events and crash recovery, and enforce source policy on result retrieval.

The migration is committed as SQL but migration-runner wiring and PostgreSQL integration tests are outstanding. Unit tests are committed but have not been run in this GitHub editing session.
