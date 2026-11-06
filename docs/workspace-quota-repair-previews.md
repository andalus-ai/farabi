# Workspace quota repair previews

Quota repair previews are operator-only, workspace-scoped, immutable proposals. They do not change quota counters, reservations, datasets, or provider objects.

## API

- `POST /api/storage/usage/repair-previews` — performs a fresh storage accounting reconciliation and persists a proposal.
- `GET /api/storage/usage/repair-previews?limit=20` — lists recent proposals for the authenticated workspace.
- `GET /api/storage/usage/repair-previews/{planId}` — reads one workspace-scoped proposal.

The workspace and actor are derived from the authenticated identity. All endpoints use the same fail-closed `DatasetStorageOperatorAuthorizer` policy as storage inventory operations.

## Proposal semantics

The proposal compares:
- ledger used bytes with the sum of storage bytes on non-archived dataset records;
- ledger reserved bytes with reservations in `RESERVED` state;
- committed reservation bytes with active dataset storage metadata.

The proposed used and reserved values are the independently observed aggregates. Deltas are proposed value minus ledger value. A proposal is marked `applicable=false` if the proposed used plus reserved bytes exceed the configured quota, or if committed reservation bytes do not reconcile with active dataset bytes. This flag is diagnostic only and is not an approval.

Previews expire after the configured lifetime (`farabi.storage.quota-repair-preview.lifetime`, default `PT15M`, maximum one hour). Records are append-only. Application is available only through the separate explicit-approval endpoint described below; preview creation itself never mutates quota counters.

## Explicit approval and application

A proposal may be applied only through an explicit operator action:

- `POST /api/storage/usage/repair-previews/{planId}/apply`

The caller is the approver; the identity is taken from the authenticated principal, and the workspace is taken from the authenticated workspace claim. The endpoint uses the same fail-closed operator authorizer as preview creation.

Application is transactional. The JDBC adapter locks the immutable preview and workspace ledger, re-reads the current quota counters and independently aggregates active dataset bytes, active reservations, and committed reservation bytes. It rejects expired previews, inapplicable previews, changed preview contents, and any ledger or aggregate drift since preview creation with HTTP 409. It also checks that committed reservation bytes still equal active dataset bytes and that the recomputed used plus reserved total fits the current quota.

On success, the used and reserved counters are updated and an immutable application audit row is inserted in the same database transaction. The application table has a unique preview reference, so retries return the original application instead of applying the adjustment twice. The audit record captures the approver and before/after counters. Database triggers reject update and delete operations on application audit rows.

No dataset rows, reservation rows, object-store objects, or quota limit are changed. Only the two aggregate counters are repaired. If underlying metadata is inconsistent, the preview is marked inapplicable or application is rejected; operators must resolve that source-of-truth inconsistency first.
