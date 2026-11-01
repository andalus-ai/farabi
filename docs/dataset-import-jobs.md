# Durable dataset import jobs

Farabi separates upload acceptance from profiling. The submission service authorizes the caller and stages the source, then registers the dataset and enqueues its profiling job in one JDBC transaction. The request thread does not hold profiling open.

## Lifecycle

- `QUEUED`: accepted and waiting for a worker or retry backoff to elapse.
- `RUNNING`: claimed with an expiring lease and a unique fencing token.
- `SUCCEEDED`: profiling and profile persistence completed.
- `FAILED`: retry budget exhausted or a final worker lease expired.

A worker crash leaves a `RUNNING` job whose lease eventually expires. Another worker can reclaim it. Completion, renewal and failure mutations require the current lease token and an unexpired lease. Stale workers cannot overwrite the result after a reclaim. Attempts increment on each claim; profiling failures use bounded exponential backoff (5 seconds up to 15 minutes).

## Atomic submission and idempotency

`JdbcDatasetImportJobStore.registerAndEnqueue` inserts the dataset and job in one database transaction. A failure inserting the job rolls back the dataset insert, preventing orphaned registrations. The idempotency key is unique within a workspace; repeating a request returns the original job. The submission service checks the key before staging to avoid unnecessary duplicate uploads in the normal retry path.

A narrow race between concurrent first submissions can still stage duplicate temporary objects before the database uniqueness constraint selects the winning job. Staged-object retention/garbage collection should clean such unreferenced uploads.

## Worker execution

1. Claim with worker ID, current time and lease expiry using PostgreSQL `FOR UPDATE SKIP LOCKED`.
2. Resolve the staged source through workspace-scoped dataset metadata.
3. Profile and persist the profile.
4. Mark success with the lease token.
5. On failure, schedule retry using the bounded backoff and the same fencing token.

The Quarkus runtime now provides a scheduled polling worker and a separate lease-heartbeat tick. Multiple replicas coordinate through database row locks and lease tokens. Enable the worker explicitly with `FARABI_IMPORT_WORKER_ENABLED=true`; configure polling, heartbeat, lease duration, worker ID and local storage root through the `FARABI_IMPORT_*` environment variables.

## Current deployment boundary

The Quarkus worker currently profiles staged local-file references. Its upload content provider intentionally fails closed until an authenticated upload/API adapter is installed. Local filesystem staging is for development or single-node deployments; production multi-replica deployments should replace it with encrypted shared object storage. HTTP import endpoints, durable object cleanup, dead-letter administration, metrics and production identity-backed authorization remain separate work.
