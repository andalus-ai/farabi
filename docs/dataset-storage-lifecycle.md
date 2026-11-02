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

## Retention policy governance

Migration V17 adds append-only-by-version retention policies, dataset exemptions and an
append-only audit log. Policies are scoped to a workspace and optionally to a dataset source.
A source-specific policy takes precedence over the workspace default. Policy versions are
created as new rows; existing versions are retained and linked through
`supersedes_policy_id`. Retention is measured from dataset `created_at`.

Operator API (all routes derive workspace and actor from the authenticated identity and use
the fail-closed storage operator authorizer):

- `POST /api/storage/retention/policies` with `{"sourceId":"optional-source","retentionDays":365,"effectiveAt":"optional-ISO-8601","reason":"..."}`
- `GET /api/storage/retention/policies?limit=50`
- `POST /api/storage/retention/datasets/{datasetId}/exemptions` with `{"reason":"...","expiresAt":"optional-ISO-8601"}`
- `POST /api/storage/retention/exemptions/{exemptionId}/revoke` with `{"reason":"..."}`
- `GET /api/storage/retention/preview?limit=100`

The preview is read-only. It lists datasets whose applicable retention clock has elapsed and
marks active legal holds separately. Active exemptions suppress a dataset from the preview.
No policy means no deletion: deletion requests fail closed unless an effective policy exists
or an explicit active exemption applies. The deletion worker re-evaluates the effective
policy and exemption expiry at claim time, so an exemption that expires while a job is queued
does not authorize a later deletion. An active legal hold independently blocks deletion,
even if retention has elapsed or an exemption exists.

Retention policies establish minimum preservation periods; they do not automatically delete
datasets. This phase does not yet implement maker-checker deletion approval or a retention
scheduler. Eligible datasets still require the existing separately authorized deletion flow.

## Maker-checker deletion approvals

Migration V18 introduces a separate approval record and immutable decision audit. A requester
cannot approve or reject their own request. Approval decisions are one-way; request identity,
dataset, reason and expiry are immutable. Approval lifetime defaults to 24 hours and is
configurable with `FARABI_DATASET_DELETION_APPROVAL_LIFETIME` (for example, `PT24H`).

API (workspace and actor are derived from the authenticated identity):

- `POST /api/dataset-deletion-approvals` with `{"datasetId":"...","reason":"..."}` submits a request.
- `POST /api/dataset-deletion-approvals/{approvalId}/approve` with `{"reason":"..."}` approves.
- `POST /api/dataset-deletion-approvals/{approvalId}/reject` with `{"reason":"..."}` rejects.
- `GET /api/dataset-deletion-approvals?limit=50` lists workspace requests.
- `GET /api/dataset-deletion-approvals/{approvalId}` reads a workspace-scoped request.

Request submission uses the storage operator authorizer. Approval and rejection use a separate
`DatasetDeletionApprovalAuthorizer`; its default CDI implementation denies all decisions.
Deployments must supply an RBAC implementation that grants approval permission independently
from request permission. The store also enforces requester/approver identity separation.

Approval revalidates the active legal hold, effective retention policy and exemption state in
the same transaction that creates the deletion job. The dataset is locked throughout. The
worker only claims a job whose exact approval record is APPROVED and references that job.
It rechecks holds, retention and exemption expiry at claim time. Policy changes and exemption
revocation serialize with deletion claims and are rejected while physical deletion is running.
Expired pending requests are marked EXPIRED when a new request is submitted for the same
dataset or when a decision is attempted.

Direct calls through the legacy deletion request port now fail closed. Existing deletion jobs
created before V18 have no approval record and are deliberately not claimed by the worker;
operators must review and resubmit them through the approval API. No automatic migration
approves historical deletion work.

## Operational follow-ups

Retention policies, retention-based automatic deletion, maker-checker deletion approvals, and
legal-hold policy administration remain separate follow-up work. A legal hold is not a retention
policy and does not itself schedule deletion. Failed deletion rows should be monitored and
reconciled; object-store lifecycle rules must not independently expire live dataset objects.
