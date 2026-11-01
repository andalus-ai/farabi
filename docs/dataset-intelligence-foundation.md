# Dataset intelligence foundation

This document describes the first engine-owned dataset catalog contract. It is a foundation, not a complete ingestion or profiling pipeline.

## Ownership and boundaries

- Farabi owns workspace-scoped dataset identity, catalog lifecycle, metadata contracts, authorization orchestration, and lineage-facing identifiers.
- Data-source adapters own reading bytes or querying provider catalogs. A location reference is opaque to the engine; it is not a filesystem path or credential.
- Profilers own schema inference and descriptive measurements. The engine stores provider-neutral profile results.
- Aljabr owns statistical and mathematical algorithms. Dataset profiling metadata must not grow into a second statistical engine.
- Runtime adapters must derive workspace and subject identity from trusted server-side authentication context, never from an untrusted client payload.

## Registration flow

1. Runtime resolves the authenticated subject and workspace.
2. The application service calls `DatasetPorts.Authorizer` with workspace, subject, and source.
3. Only after authorization succeeds does the service construct a `REGISTERED` dataset and call the catalog port.
4. The catalog adapter atomically enforces uniqueness of dataset name within a workspace.
5. A failed authorization or duplicate insert does not create a catalog entry.

The catalog lookup contract always includes the workspace ID. Adapters must enforce that scope in the persistence query, not fetch globally and filter only in application memory.

## Contracts

- `DatasetContracts.Dataset`: immutable identity, workspace, source, format, opaque location reference, lifecycle, and timestamps.
- `DatasetContracts.Profile`: immutable row count, column descriptors, profile timestamp, and profiler version.
- `DatasetContracts.Column`: logical type, nullability, null count, and distinct count.
- `DatasetPorts.Catalog`: atomic registration, workspace-scoped retrieval, and profile persistence.
- `DatasetCatalogService`: authorization-before-write application service.

Profiles are descriptive observations, not permissions, semantic definitions, or guarantees that a future query is authorized. Query execution must continue to enforce the current dataset and column policies.

## Current limitations / next vertical slice

This change does not implement file upload, format parsers, physical storage, schema inference, sampling, profile workers, catalog persistence, API endpoints, or a real authorization adapter. The next implementation should connect one ingestion adapter and one catalog persistence adapter, add a durable profiling job lifecycle, and prove workspace isolation against a real database.
