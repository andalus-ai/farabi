# Workspace storage accounting and quotas

Farabi now accounts for stored dataset bytes per workspace. The quota is enforced before the
upload is staged, so concurrent uploads cannot each observe the same remaining capacity and
overcommit it.

## Upload accounting flow

1. The authenticated import service authorizes the source.
2. A quota reservation is created for the declared upload length. The database atomically
   increments `reserved_bytes` only when `used_bytes + reserved_bytes + requested_bytes`
   remains within `quota_bytes`.
3. The storage adapter stages the upload and reports the actual byte count. Local storage uses
   the counted stream bytes; S3 storage verifies the object size with `HeadObject`.
4. Dataset metadata, import job, reservation consumption, and usage increment are committed in
   one JDBC transaction. The actual byte count must not exceed the reservation.
5. If staging or registration fails, the staged object is deleted and the reservation released.
   Replayed idempotency requests do not reserve capacity a second time.

## Deletion accounting

The durable deletion worker releases `used_bytes` in the same transaction that marks the
deletion job successful and the dataset archived. A retry after an object-store delete but before
database completion is safe because object deletion is idempotent and the usage decrement is
transactional. Legacy datasets created before this migration have `storage_bytes = 0`; their
deletion does not decrement a ledger that was never populated.

## Schema and configuration

Migration: `V8__farabi_workspace_storage_accounting.sql`.

- `farabi_workspace_storage`: quota, committed usage, and outstanding reservations.
- `farabi_storage_reservations`: idempotent reservation state linked to the dataset after commit.
- `farabi_datasets.storage_bytes`: verified object size.

The default quota for newly seen workspaces is 10 GiB:

```properties
FARABI_IMPORT_STORAGE_DEFAULT_QUOTA_BYTES=10737418240
```

An operator can set a workspace-specific `quota_bytes` in `farabi_workspace_storage`, provided
the new value is not below `used_bytes + reserved_bytes`. The runtime does not expose a
workspace quota mutation endpoint; quota changes remain an administrative operation.

Authenticated workspace usage is available at `GET /api/storage/usage`. It returns quota,
committed bytes, reserved bytes, and available bytes; no caller-supplied workspace identifier is
accepted.

## Operational notes

The accounting source of truth is the relational ledger. Reconciliation against actual object
storage inventory is still needed to detect out-of-band writes, manual object deletion, and
legacy storage that predates accounting. Do not let bucket lifecycle rules expire live dataset
objects independently of Farabi's deletion workflow.
