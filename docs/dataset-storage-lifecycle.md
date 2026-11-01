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

## Operational follow-ups

This first lifecycle slice deliberately does not implement retention policies, legal holds,
workspace quotas/usage accounting, orphan-object discovery, or an HTTP delete endpoint. Those
need explicit policy and audit requirements. Failed deletion rows should be monitored and
reconciled; object-store lifecycle rules must not independently expire live dataset objects.
