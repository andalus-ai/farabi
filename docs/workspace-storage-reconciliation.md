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
