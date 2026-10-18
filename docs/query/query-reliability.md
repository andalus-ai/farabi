# P2 — Query execution reliability (incremental)

This increment introduces a **pure recovery-decision policy** and an immutable audit-event model. It does not claim to provide a production worker, cancellation, outbox, or durable audit log.

## Recovery decisions

- Terminal jobs: NONE.
- Expired nonterminal jobs: EXPIRE (a decision only; no remote cancellation occurs).
- Active jobs with a provider job ID: RECONCILE_REMOTE.
- Stale PENDING jobs without a provider ID: INVESTIGATE_SUBMISSION; **do not blindly resubmit** because Dremio may have accepted the request before the client observed a transport failure.
- Other jobs: NONE.

## Required follow-ups

1. Add a database-backed worker lease and compare-and-swap claim mechanism.
2. Implement transactional outbox and idempotency key handling across job creation and submission.
3. Persist audit events in an append-only store with actor attribution and retention policy.
4. Add Dremio cancellation with authenticated authorization and observed terminal-state reconciliation.
5. Add worker polling, bounded backoff, dead-letter handling, timeouts, and integration tests.

No Maven or live Dremio test execution was performed in this editing session.
