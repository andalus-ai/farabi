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
