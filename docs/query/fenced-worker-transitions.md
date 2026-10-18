# P2 — Fenced worker state transitions

This milestone adds an incrementing PostgreSQL `lease_epoch` and a worker-only JDBC operation that changes job state and appends an audit event **within one transaction**.

## Properties

- Acquiring a lease increments the epoch. Old epochs cannot update a job even if the worker ID is reused.
- Transitions check the active lease, matching epoch, expected status, and unexpired job.
- The audit row derives workspace and subject identity from the persisted job, rather than trusting caller-provided audit identities.
- Transition and audit insertion commit or roll back together.

## Remaining gaps

- No scheduler, worker polling loop, cancellation or idempotent Dremio submission.
- Database fencing cannot prevent stale workers from issuing **external** Dremio operations. An outbox/idempotency design is still necessary.
- This implementation depends on `QueryJob` (PR #8), `QueryExecutionAudit` (PR #10), and the lease/audit schema (PR #11).
- PostgreSQL integration tests, migration validation and Maven tests have not been executed.
- The caller must be trusted backend code. Do not expose the lease and transition operations directly to users.
