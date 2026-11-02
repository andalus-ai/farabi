# Dataset storage lifecycle

Dataset deletion is an asynchronous, durable operation. The API/service layer must authorize the
workspace and subject before calling `DatasetDeletionService.request`. The service records a
`DELETE_PENDING` tombstone and a deletion outbox row in one database transaction. The dataset
metadata and profiles remain available for audit until the object deletion succeeds.

## Processing

- `JdbcDatasetDeletionStore` claims work with PostgreSQL `FOR UPDATE SKIP LOCKED`.
- Each claim receives a fencing token and expiry. Completion and retry updates require the
  current token and an unexpired lease.
- Object deletion is idempotent: if the process crashes after deleting the object but before
  committing completion, the retry safely deletes the same key again.
- Successful completion marks the dataset `ARCHIVED` and records `deleted_at`; metadata is
  retained rather than hard-deleted.
- Failures use bounded exponential retry (5 seconds to 15 minutes). Exhausted jobs remain
  `FAILED` for operator reconciliation; they are not silently discarded.

## Storage boundaries

The local object store only supports development/single-node local-file references. S3 deletion
validates the bucket and workspace-hashed key prefix before issuing DeleteObject. Do not allow
clients to submit storage references: deletion requests must resolve a dataset ID inside the
authenticated workspace.

The default deletion authorizer denies all requests. A deployment must provide an explicit
`DatasetStorageLifecyclePorts.Authorizer` implementation before exposing deletion operations.
The worker can be enabled with the existing `FARABI_IMPORT_WORKER_ENABLED` scheduler switch.

## Legal holds

Migration V16 adds workspace-scoped legal holds and an append-only placement/release audit log.
A hold records its reason, placing actor and timestamp; release requires a separate reason and
records the releasing actor and timestamp. Hold records remain as history after release.

Operator API (workspace is derived from the authenticated identity):

- `POST /api/storage/legal-holds/datasets/{datasetId}` with `{"reason":"..."}` places a hold.
- `POST /api/storage/legal-holds/{holdId}/release` with `{"reason":"..."}` releases it.
- `GET /api/storage/legal-holds?datasetId={uuid}&limit=50` lists recent holds.

All endpoints use the existing fail-closed storage operator authorizer. A deployment must replace
the default-deny authorization policy with its RBAC implementation.

Deletion requests fail while a hold is active, and the worker does not claim held datasets. Claiming
locks both the deletion job and dataset row; hold placement takes the dataset lock and rejects a
hold if deletion is already running. This serializes the critical race: either the hold is committed
first and deletion cannot be claimed, or deletion has already started and the hold is rejected.
After a deletion job is queued but not running, a hold may be placed and pauses future claims.

## Operational follow-ups

Retention policies, retention-based automatic deletion, maker-checker deletion approvals, and
legal-hold policy administration remain separate follow-up work. A legal hold is not a retention
policy and does not itself schedule deletion. Failed deletion rows should be monitored and
reconciled; object-store lifecycle rules must not independently expire live dataset objects.
