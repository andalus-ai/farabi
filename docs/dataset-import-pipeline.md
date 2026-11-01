# Dataset import pipeline

Farabi coordinates dataset import through replaceable ports:

1. The caller submits workspace, subject, source, file metadata and declared format.
2. `DatasetPorts.Authorizer` authorizes workspace/source access before intake is allowed to persist bytes.
3. `DatasetImportPorts.SourceIntake` stages the content and returns a durable opaque location reference.
4. Farabi creates a workspace-scoped catalog record.
5. `DatasetImportPorts.Profiler` inspects the staged source and returns a typed, reproducible profile.
6. The catalog persists the profile; the caller receives the dataset and profile together.

The engine does not parse file formats or execute user-supplied code. Concrete CSV/XLSX/JSON/Parquet readers, object storage, antivirus/content validation, streaming limits, asynchronous jobs and lifecycle transitions belong in adapters/runtime integration. Intake implementations must validate file size and type and must not trust client-provided content type or filename. Profilers must enforce row/column/time/memory limits and return only metadata, not raw cell contents.

A profile failure leaves the dataset registered without a profile so a retryable profiling job can recover it. Duplicate workspace/name registration is rejected by the catalog. Authorization is always performed before intake.
