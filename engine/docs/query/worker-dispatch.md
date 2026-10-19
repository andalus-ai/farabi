# P2 Worker Dispatch Pass

`QueryJobWorkerDispatcher` is a synchronous, bounded processing primitive. It accepts candidate IDs, attempts an atomic fenced lease claim, and invokes a trusted handler only for claimed jobs.

## Integration

Adapt `JdbcRecoverableJobScanner::scan` as `CandidateSource` and `JdbcFencedJobWorkerStore::claim` as `LeaseClaimer`. A future scheduled service can call `runOnce` with a trusted worker identity, TTL, and bounded batch size.

## Known limitations

- No scheduler, persisted next-attempt time, jitter, cancellation, heartbeat or distributed rate limiting.
- The dispatcher does not release leases: they expire unless the handler coordinates release/renewal. Handler failures are counted but swallowed, and require separate structured logging/metrics.
- Database fencing does not make Dremio submission idempotent.
- This is not a production-ready background worker. Unit tests are committed but not executed.
