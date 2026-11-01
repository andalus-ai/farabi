# Workspace storage reconciliation

The read-only reconciliation foundation compares workspace quota counters with dataset and reservation metadata.

## Checks
- Workspace used bytes versus the sum of storage bytes for datasets not in ARCHIVED lifecycle.
- Workspace reserved bytes versus reservation rows in RESERVED state.
- Committed reservation bytes versus non-archived dataset storage bytes.

Each inspection persists an immutable, workspace-scoped aggregate report. Reports contain byte counts and finding types, not object keys or other workspaces' metadata. The service requires an explicit workspace ID; report-history reads are bounded to 100 rows.

## Safety boundary
This phase is detection-only. It never adjusts quota counters, changes dataset lifecycle, deletes objects, or releases reservations. Database metadata does not prove an object exists in S3 or on local disk.

Provider inventory (S3 HEAD/LIST and secure filesystem inspection), orphan staging detection with a grace period, and audited repair actions are subsequent phases. Do not expose this service through an ordinary authenticated endpoint until an explicit operator/admin authorization policy is wired.

Wire JdbcDatasetStorageReconciliationStore as the engine Store and invoke DatasetStorageReconciliationService from a trusted operator workflow. A finding signals investigation; it is never an instruction to mutate accounting.


## Provider inventory (second stage)

The provider inventory port is implemented by the S3-compatible and local-file adapters. It supports workspace-scoped object listing and per-reference inspection. The inventory service compares provider objects with non-archived catalog references and reports:

- Missing objects referenced by the catalog.
- Object sizes that differ from the catalog's recorded size.
- Unreferenced objects older than the configured orphan grace period.

The default grace period is 24 hours and is configurable with `FARABI_IMPORT_STORAGE_ORPHAN_GRACE_PERIOD` (ISO-8601 duration, for example `PT48H`). Newer unreferenced objects are intentionally suppressed because an upload may be staged before its database registration commits.

This stage remains read-only. It does not delete or repair any object. The S3 implementation lists only the hashed workspace prefix; the local implementation lists only the workspace's hashed directory, skips symbolic links, and accepts UUID-named supported data files only. Provider inventory findings are currently returned by the application service and are not yet exposed as an HTTP endpoint or persisted as a second report family.


## Persistent provider inventory reports and operator API

Provider inventory snapshots are now stored in `farabi_storage_inventory_reports`; individual findings are stored in `farabi_storage_inventory_findings`. Header and finding rows are committed in one transaction. History queries are workspace-scoped and limited to 100 reports.

The authenticated operator API is:

- `POST /api/storage/inventory` — perform and persist an inventory inspection for the caller's workspace.
- `GET /api/storage/inventory?limit=20` — retrieve recent persisted reports for that workspace.

The API obtains workspace and subject identity from the authenticated security identity. It does not accept a caller-supplied workspace ID. Both operations invoke `DatasetStorageOperatorAuthorizer`. The built-in CDI implementation denies every request; deployments must replace it with their explicit operator RBAC policy. Authentication alone is intentionally insufficient.

Reports include storage location references in findings. Treat this endpoint and its database tables as operator-sensitive data. Apply retention and access monitoring before enabling it for production operators.


## Durable inventory job execution

Migration V11 adds `farabi_storage_inventory_jobs`, with workspace-scoped idempotency, bounded attempts, retry scheduling, lease owner/token/expiry, and report correlation. PostgreSQL workers claim work atomically using `FOR UPDATE SKIP LOCKED`; every completion/failure update is fenced by the current lease token. Expired leases can be reclaimed, and a lease expiring after the final attempt is terminally failed.

The Quarkus scheduler is opt-in. Set `FARABI_STORAGE_INVENTORY_POLL_INTERVAL` to an interval such as `2s` and `FARABI_IMPORT_WORKER_ENABLED=true` to enable the scheduler. Configure `FARABI_STORAGE_INVENTORY_WORKER_ID`, `FARABI_STORAGE_INVENTORY_LEASE_DURATION`, and `FARABI_STORAGE_INVENTORY_MAX_ATTEMPTS` as needed. Polling processes at most ten jobs per tick. This worker only creates read-only inventory reports; it does not delete objects or repair accounting.

The operator authorizer remains fail-closed by default. Ensure the workspace has a storage-accounting row before submitting jobs, since the queue is workspace-FK scoped.


## Operator and worker audit trail

Migration V12 adds `farabi_storage_inventory_audit`. The application appends events for operator submission and worker success, retry scheduling, and terminal failure. Events capture workspace, job, actor (authenticated subject or worker identity), attempt, report correlation, error code, and timestamp. Duplicate event delivery is idempotent. A PostgreSQL trigger rejects UPDATE and DELETE so audit rows are immutable.

The operator-only endpoint `GET /api/storage/inventory/audit?limit=50` returns recent events for the authenticated workspace; add `jobId` to narrow the history to one job. The caller cannot choose a workspace, and the same fail-closed operator authorizer is applied.

The audit table intentionally excludes idempotency keys, object location references, and raw exception messages. Audit events are appended after the corresponding job transition. If audit persistence is unavailable, the API submission may return an error after the idempotent job has been enqueued; retrying the same idempotency key is safe. Retention, export, and external tamper-evident archival remain deployment responsibilities.
