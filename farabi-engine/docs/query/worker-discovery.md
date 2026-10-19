# P2 — Bounded worker discovery and retry backoff

This increment adds two isolated building blocks:

1. `JdbcRecoverableJobScanner.scan(limit)` discovers up to 500 PENDING/SUBMITTED/RUNNING jobs whose leases are absent or expired, ordered by oldest update. Discovery is not a claim and is intentionally allowed to race; callers must claim with a fenced atomic update before work.
2. `QueryWorkerBackoff` computes deterministic exponential delays with a cap and rejects invalid bounds.

## Integration requirements

- A worker scheduler must call the scanner, atomically claim with `JdbcFencedJobWorkerStore`, apply `QueryJobRecoveryPolicy`, then reconcile Dremio and commit fenced transitions.
- Persist `next_attempt_at` before enabling retries: currently the scanner does **not** apply a backoff schedule, so repeated scans can hot-loop. Expired jobs are included for recovery decisions; they cannot be claimed by the existing lease store, so a separate expiration workflow is needed.
- Add observability, concurrency limits, jitter, shutdown handling and a retry budget.
- The current query-job schema and fenced worker code are in draft PRs #8, #10, #11 and #12. This PR is not independently buildable.
- No Maven or PostgreSQL tests were executed in this session.
