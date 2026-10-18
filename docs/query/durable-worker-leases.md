# P2 durable worker leases — initial database primitives

This increment adds a PostgreSQL migration for worker leases and append-only audit records, plus JDBC lease claim/renew/release and audit append operations.

## Safety properties

- Claim is a single conditional UPDATE and only succeeds when the prior lease has expired or is absent.
- Renewal requires the same worker ID, a still-active lease, an unexpired job and a nonterminal state.
- Release requires the matching worker ID.
- Expired jobs and terminal jobs cannot be newly claimed.
- Audit events are append-only rows with unique event IDs.

## Not yet production-ready

The lease does **not** have a monotonically increasing fencing token; a worker that lost a lease could still perform a downstream side effect. The job-state CAS method from the earlier milestone does not enforce lease ownership. Audit append and job transition are separate transactions. Implement transactionally coupled transitions, fencing, recovery scanning, and a scheduler before deploying. Add PostgreSQL integration tests and ensure migration ordering; this branch depends on the job schema and audit model from earlier draft PRs.

No database or Maven tests were run in this editing session.
