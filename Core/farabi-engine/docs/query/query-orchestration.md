# P2 query orchestration — initial integration

`QueryExecutionCoordinator` connects governed semantic submission, persisted BI job metadata, Dremio status lookup and paginated result retrieval.

## Boundaries

- Creates a server-owned BI job record and submits the authorized semantic query.
- Records Dremio's job ID against the BI job via a compare-and-swap state transition.
- Status and result reads first verify workspace membership, exact subject ownership, expiry, and source policy.
- Results are only returned after the BI job is marked `SUCCEEDED`.
- `reconcile` checks a Dremio status response before changing persisted status; it is **worker-only** and must never be wired directly to an untrusted client.

## Limitations

This is not yet a complete production job orchestrator:
- No durable outbox, idempotency key, submission reconciliation, crash recovery, or scheduled worker. A transport failure can leave an orphaned Dremio job.
- No remote cancellation endpoint or cancellation authorization flow.
- The existing Dremio transport buffers HTTP response bodies and requires endpoint-version verification.
- The status mapper recognizes `RUNNING`, `COMPLETED`, `FAILED`, `CANCELED` and `CANCELLED`. Real Dremio versions may expose additional states; unknown states fail closed.
- No Toba-backed catalog or governance implementation is included.
- No end-to-end PostgreSQL/Dremio tests have been executed.

Keep this service internal until privileged worker APIs, audit events and tenant-safe job recovery are implemented.
