# M2 — Multi-source data foundation

**Status:** Initial engine contracts and governed registry use cases implemented on `feature/m2-multisource-foundation`. Connector adapters and runtime wiring are not yet implemented or verified.

## Objective

Allow Farabi to manage multiple workspace-scoped analytical connections through provider-neutral contracts. Keep metadata persistence (`adapter-jdbc`) separate from customer data connectivity.

## Implemented in this batch

- `DataSourceContracts`: connection definitions, secret references, provider kinds, health, capabilities, catalog entries and column metadata.
- `DataSourcePorts`: connection registry, provider, provider registry, secret resolver and connection authorization ports.
- `DataSourceRegistryService`: authorization-before-registration/discovery, provider availability checks and workspace-scoped lookup.
- Unit tests for immutable connection properties, rejection of credential-bearing endpoints, unknown providers, authorization order and workspace isolation.

## Security invariants

1. Domain connection definitions contain secret references, never passwords or tokens.
2. Endpoint URIs cannot embed user-info, query parameters or fragments.
3. Registration and discovery require an explicit authorization decision.
4. Source lookup is scoped by workspace.
5. Provider adapters are selected by a stable provider key and must not become authorization authorities.
6. Read-only is an explicit connection property; adapters must enforce it at the connection/session level, not merely trust the flag.

## Next implementation batches

### M2-A — Generic JDBC adapter

- Add a dedicated `adapter-jdbc-datasource` module; do not repurpose `adapter-jdbc`, which stores Farabi metadata.
- Implement connection creation using an injected connection factory/secret resolver boundary.
- Discover catalogs, schemas, tables/views and columns using JDBC metadata.
- Map JDBC types into Farabi logical types while preserving native type names.
- Add dialect SPI for PostgreSQL and MySQL behavior.
- Add H2 tests and PostgreSQL/MySQL Testcontainers contract tests.

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

The GitHub source changes have been committed to the feature branch. Maven compilation, unit tests and integration tests have **not** been run in this environment. Do not treat this batch as build-verified or production-ready.
