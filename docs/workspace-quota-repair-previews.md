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

Previews expire after the configured lifetime (`farabi.storage.quota-repair-preview.lifetime`, default `PT15M`, maximum one hour). Records are append-only. There is deliberately no apply, approve, or quota mutation endpoint in this phase. A later application workflow must re-read current values, require explicit operator approval, revalidate the preview, and apply changes transactionally with an audit trail.
