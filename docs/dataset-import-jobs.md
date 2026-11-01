# Durable dataset import jobs

Farabi separates upload acceptance from profiling. The API authorizes and stages the source, registers the dataset, then enqueues a durable job. A worker claims the job and invokes the existing profiler port; the request thread does not hold the profiling operation open.

## Lifecycle

- `QUEUED`: accepted and waiting for a worker.
- `RUNNING`: claimed with an expiring lease and a unique fencing token.
- `SUCCEEDED`: profiling and profile persistence completed.
- `FAILED`: retry budget exhausted.

A worker crash leaves a `RUNNING` job whose lease eventually expires. Another worker can reclaim it. Every completion/failure mutation must present the current lease token; stale workers cannot overwrite the result after a reclaim. `attempts` increments on each claim, and failures return to `QUEUED` until `max_attempts` is reached.

## Idempotency and isolation

The idempotency key is unique within a workspace. Repeating an enqueue request returns the original job. Job lookup always includes the workspace identifier. The dataset foreign key prevents orphaned jobs; the workspace remains part of every job read and uniqueness constraint.

## Worker outline

1. Claim with worker ID, current time and lease expiry.
2. Load the dataset and staged source through workspace-scoped ports.
3. Profile and persist the profile.
4. Mark success with the lease token.
5. On a retryable error, mark failure with the lease token; the store requeues while attempts remain.

The JDBC claim uses row locking with `SKIP LOCKED`, suitable for concurrent PostgreSQL workers. Lease duration, heartbeat/renewal, backoff scheduling, dead-letter operations, metrics and API endpoints are follow-up work. The current job store is the persistence primitive, not a complete worker deployment.
