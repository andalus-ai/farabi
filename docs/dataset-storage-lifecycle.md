# Dataset storage lifecycle

Dataset deletion is a maker-checker governed, asynchronous durable operation. The requester submits
an approval request; a separately authorized checker approves it. Only that approval transaction
creates the `DELETE_PENDING` tombstone and deletion outbox row. Dataset metadata and profiles
remain available for audit until object deletion succeeds.

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
validates the bucket and workspace-hashed key prefix before issuing DeleteObject. Both providers
must support an authoritative workspace-scoped existence check; inability to verify absence is a
failure, not success. Do not allow
clients to submit storage references: deletion requests must resolve a dataset ID inside the
authenticated workspace.

The default storage operator authorizer and the separate deletion approval authorizer both deny
access until deployment supplies explicit RBAC implementations. The worker can be enabled with
the existing `FARABI_IMPORT_WORKER_ENABLED` scheduler switch.

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
datasets. Eligible datasets proceed through the maker-checker approval flow described below.

## Deletion execution audit (V19)

The worker writes immutable execution events in the same database transaction as each
state transition. Migration V19 adds `farabi_dataset_deletion_execution_audit` with
workspace- and dataset-scoped foreign keys, a unique job/event/attempt key, and a
PostgreSQL trigger rejecting updates and deletes.

Recorded events:

- `CLAIMED`: a worker acquired a fenced lease for an attempt.
- `LEASE_EXPIRED`: a nonterminal worker lease expired and its attempt was reclaimed.
- `RETRY_SCHEDULED`: object deletion failed and the job was returned to the queue.
- `FAILED`: the configured attempt budget was exhausted after an object deletion failure.
- `LEASE_EXHAUSTED`: a worker lease expired on the final allowed attempt.
- `OBJECT_ABSENT_VERIFIED`: after the provider delete call, a workspace-scoped lookup
  confirmed that the object no longer exists.
- `SUCCEEDED`: the dataset lifecycle/storage accounting transaction committed after that
  absence verification.

The claim, retry/failure, lease-exhaustion, and success audit writes are transactionally
coupled to their respective job transitions. The absence-verification event is committed in
the same transaction as archival and quota-ledger updates. A stale lease token cannot complete or fail a
job and cannot write an event through those transitions. The audit records attempt, worker,
lease token, error category, and timestamp; they intentionally do not persist exception
messages or object-store credentials.

Read execution history with
`GET /api/dataset-deletion-approvals/jobs/{jobId}/audit?limit=50`.
The endpoint is workspace-scoped and requires the storage operator authorization boundary.
A job outside the caller's workspace returns an empty list, not another workspace's data.

Migration V20 adds the `OBJECT_ABSENT_VERIFIED` audit event. The deletion worker now requires an explicit provider absence check before archival. The local
adapter checks the workspace-scoped file path; the S3 adapter performs a post-delete HEAD lookup.
If the object remains visible or the provider cannot confirm absence, the job is retried and the
dataset remains unarchived with its quota usage retained. This closes the crash window where an
object delete could succeed but database accounting could be committed prematurely.

Operator-controlled recovery is documented below. Periodic cross-system reconciliation of
archived datasets against provider inventory remains a separate follow-up.

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
- `GET /api/dataset-deletion-approvals/{approvalId}/audit` reads its immutable decision history.

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
created before V18 have no approval record and are deliberately not claimed by the worker.
Queued or failed legacy jobs can be reviewed and re-bound to a new maker-checker approval;
the existing job is reused rather than duplicating the dataset's unique deletion row. A legacy
job already RUNNING requires manual reconciliation because object deletion may have started.
No automatic migration approves historical deletion work.

## Cross-system deletion reconciliation (V21)

Migration V21 adds immutable, workspace-scoped deletion reconciliation reports and findings.
The operator-triggered reconciliation is read-only: it inspects the catalog, deletion outbox,
provider object state, and quota ledger without deleting objects or changing lifecycle/accounting.

- `POST /api/storage/deletion-reconciliation` creates a fresh report.
- `GET /api/storage/deletion-reconciliation?limit=20` reads recent reports for the authenticated workspace.
- Both routes require the storage operator authorization boundary; workspace scope comes only
  from the authenticated identity.

Findings include:

- `ARCHIVED_OBJECT_PRESENT`: an object still exists for a dataset marked ARCHIVED.
- `SUCCEEDED_DATASET_NOT_ARCHIVED`: a successful deletion job is linked to a dataset that is
  not ARCHIVED.
- `ARCHIVED_STORAGE_BYTES_NONZERO`: archived metadata still carries committed storage bytes.
- `LEDGER_USED_MISMATCH`: workspace used bytes differ from the sum of non-archived dataset bytes.
- `LEDGER_RESERVED_MISMATCH`: workspace reserved bytes differ from active RESERVED rows.

Reports and findings are append-only. Provider lookups are workspace-fenced. A provider lookup
failure aborts report creation rather than being interpreted as absence. The synchronous snapshot
is bounded to 5,000 datasets and 5,000 deletion jobs per workspace; larger workspaces require a
future partitioned/queued reconciliation runner. The report is a
diagnostic snapshot, not a repair command: operators must review evidence and use the existing
governed deletion or quota-repair workflows. No finding triggers automatic object deletion,
dataset archival, quota adjustment, or reservation release.

## Operator-controlled deletion recovery

A terminally failed deletion is never retried merely because a worker restarts. Migration V19 extends the immutable execution audit with `RECOVERY_QUEUED` events that record the operator, mandatory reason, and recovery sequence. Recovery reuses the original deletion job and its maker-checker approval; it does not create a new approval or a duplicate job.

- `POST /api/dataset-deletion-approvals/jobs/{jobId}/recover` with `{"reason":"..."}` requests a recovery.
- Recovery permission is separate from both request/approval permission and the general storage-operator permission. The default `DatasetDeletionRecoveryAuthorizer` denies all requests; deployments must provide explicit RBAC.
- Only a `FAILED` job whose exact linked approval remains `APPROVED` can be recovered. The dataset must still be `DELETE_PENDING`.
- The transaction locks the dataset and job, rechecks active legal holds and the current effective retention policy or active exemption, resets the attempt counter, and queues the same job with a bounded retry budget (1..20; API uses the configured deletion max attempts).
- A reason is mandatory (1..2000 characters). The recovery event is committed atomically with the queue transition. Repeated recovery attempts against a queued/running/succeeded job return conflict; another recovery is possible only after a subsequent terminal failure.

Recovery is an explicit operator action, not a relaxation of maker-checker approval or retention policy. The worker continues to enforce legal holds and retention at claim time. Execution audit history includes the recovery actor and reason.

## Operational follow-ups

Retention-based automatic deletion and legal-hold policy administration remain separate follow-up
work. A legal hold is not a retention policy and does not itself schedule deletion. Failed deletion rows should be monitored and recovered only through the governed recovery endpoint;
object-store lifecycle rules must not independently expire live dataset objects.
