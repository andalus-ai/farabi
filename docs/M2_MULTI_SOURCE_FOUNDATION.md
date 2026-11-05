# M2 — Multi-source data foundation

**Status:** Initial engine contracts, governed registry use cases, generic JDBC metadata discovery and a bounded synchronous JDBC query adapter are committed on `feature/m2-multisource-foundation`. Runtime wiring, durable/asynchronous execution and real PostgreSQL/MySQL integration remain outstanding.

## Objective

Allow Farabi to manage multiple workspace-scoped analytical connections through provider-neutral contracts. Keep metadata persistence (`adapter-jdbc`) separate from customer data connectivity.

## Implemented in this batch

- `DataSourceContracts`: connection definitions, secret references, provider kinds, health, capabilities, catalog entries and column metadata.
- `DataSourcePorts`: connection registry, provider, provider registry, secret resolver and connection authorization ports.
- `DataSourceRegistryService`: authorization-before-registration/discovery, provider availability checks and workspace-scoped lookup.
- Unit tests for immutable connection properties, rejection of credential-bearing endpoints, unknown providers, authorization order and workspace isolation.
- `adapter-jdbc-source`: generic JDBC provider for catalog/schema/table/view/column discovery, JDBC-to-Farabi logical type mapping, capability/dialect identification and health checks. It receives a connection factory from the host runtime; secret resolution, pooling and driver configuration are intentionally not embedded in the adapter.
- H2 metadata tests covering schema/table/column discovery, JSON and common JDBC type mapping, capability reporting, health checks and read-only connection mode.
- `QueryContracts.Plan` now supports ordered, typed positional parameters; JDBC uses `PreparedStatement` binding and requires an explicit JDBC type for SQL NULL.
- `QueryLifecycle.ResultPage` preserves nullable values and exposes a `truncated` flag.
- `JdbcQueryProvider` implements the existing `QueryPorts.Executor` and `QueryLifecycle.Provider` contracts for bounded synchronous query execution, read-only connection mode, 30-second statement timeout, fetch-size/result/page limits, process-local LRU result retention and result paging.

## Security invariants

1. Domain connection definitions contain secret references, never passwords or tokens.
2. Endpoint URIs cannot embed user-info, query parameters or fragments.
3. Registration and discovery require an explicit authorization decision.
4. Source lookup is scoped by workspace.
5. Provider adapters are selected by a stable provider key and must not become authorization authorities.
6. Read-only is an explicit connection property; adapters must enforce it at the connection/session level, not merely trust the flag.

## Next implementation batches

### M2-A — Generic JDBC adapter (foundation committed)

- Dedicated `adapter-jdbc-source` Maven module; `adapter-jdbc` remains the Farabi metadata persistence adapter.
- Injected connection factory keeps credential resolution, pooling, driver selection and TLS policy at the runtime boundary.
- Catalog, schema, table/view and column discovery uses JDBC metadata.
- JDBC types map to Farabi logical types while native type names are preserved in metadata.
- Basic dialect identification is present; vendor-specific dialect behavior and PostgreSQL/MySQL Testcontainers contract tests remain to be implemented.
- Limitations: this first executor is synchronous, cannot cancel a query after submission, and retains results only in a bounded process-local cache. It is not a durable job system and must not be advertised as asynchronous or horizontally scalable.
- Next: runtime connection factory backed by the secret resolver and a read-only database principal, PostgreSQL/MySQL integration tests, and end-to-end wiring through the governed query use case. A durable worker-backed executor should replace the synchronous adapter before production async workloads.

### M2-B — Dremio provider alignment

- Implement the new provider contract using the existing Dremio HTTP adapter.
- Add catalog/schema/table/column discovery against supported Dremio APIs.
- Preserve existing query submission, status, results and cancellation ports.
- Add transport fixtures for success, error, unknown state, paging and cancellation.

### M2-C — Runtime connection lifecycle

- Persist source definitions separately from query execution metadata.
- Resolve secret references only at adapter boundaries.
- Add create/list/test/discover endpoints with authenticated workspace context.
- Add TLS validation, timeouts, connection limits and audit events.

### M2-D — End-to-end validation

- Prove two independent sources can be registered in one workspace.
- Prove cross-workspace lookup and discovery are denied.
- Prove query execution still passes through the governed query use case.
- Verify driver-specific metadata and type mappings against real databases.

## Deliberately deferred

- Cross-source joins and federation (delegate to Dremio/Trino).
- A home-grown connection pool.
- Automatic credential storage or plaintext secrets.
- Vendor-specific SQL rewriting in the engine.
- Snowflake/BigQuery/Databricks connectors before JDBC and Dremio contracts stabilize.

## Verification status

The GitHub source changes have been committed to the feature branch. Maven compilation, unit tests and integration tests have **not** been run in this environment. The repository CI workflow runs on branch pushes, but no successful run has been confirmed here. Do not treat this batch as build-verified or production-ready.
