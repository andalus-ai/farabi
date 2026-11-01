# S3-compatible dataset storage

Farabi provides an optional S3-compatible adapter in `adapter-s3`. The engine continues to depend only on `UploadSourceIntake` and `Profiler`; AWS SDK types remain inside the adapter/runtime boundary.

## Selection and configuration

Build the Quarkus runtime with `farabi.import.storage.provider=s3` (or `FARABI_IMPORT_STORAGE_PROVIDER=s3`) to select the S3 adapter. The default is `local`.

Configure:

- `FARABI_IMPORT_S3_BUCKET`: required bucket name.
- `FARABI_IMPORT_S3_REGION`: signing region; defaults to `ap-southeast-1`.
- `FARABI_IMPORT_S3_ENDPOINT`: optional custom endpoint for MinIO or another S3-compatible service.
- `FARABI_IMPORT_MAX_BYTES`: per-object upload limit; defaults to 512 MiB.
- `FARABI_IMPORT_TEMP_ROOT`: private local scratch directory used while profiling downloaded objects.

The SDK default credential provider chain is used (workload identity/role credentials are preferred). Do not put access keys in application properties. Path-style access is enabled for compatibility with self-hosted S3 services. The adapter requests server-side AES-256 encryption for every object.

## Object layout and isolation

Objects are written beneath `datasets/{sha256(workspaceId)}/{uuid}.{extension}`. Raw workspace identifiers, client filenames and credentials are not used in object keys. Dataset metadata stores an opaque `s3://bucket/key` reference. Every read validates that the reference belongs to the requested workspace and checks the workspace hash recorded in object metadata.

The adapter supports CSV, TSV, JSON and JSONL. Profiling downloads the object to a bounded, private local scratch directory, reuses the existing deterministic file profiler, and removes the temporary copy afterwards. Scratch storage must be encrypted or mounted on an encrypted ephemeral volume and sized for concurrent profiles.

## Operational limits

This is an S3 API adapter, not a claim that every vendor has been integration-tested. Validate endpoint, TLS, IAM/bucket policy, encryption behavior and lifecycle rules against the selected provider before production. Configure bucket versioning and lifecycle retention according to data governance requirements. The adapter includes an explicit workspace-scoped delete operation, but automatic orphan cleanup, retention scheduling, checksum audit records, malware scanning, quotas and metrics are not yet implemented.

The default Farabi dataset registration authorizer remains deny-all. S3 credentials do not replace application-level workspace/source authorization.