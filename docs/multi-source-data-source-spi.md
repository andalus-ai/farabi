# Multi-source data-source SPI (foundation)

## Objective

Provide a provider-neutral engine boundary for registered analytical sources while keeping
database-specific connection pools, drivers and metadata behavior in adapters. This is the
first implementation slice of the multi-source milestone; it is not yet a complete connector
management product.

## Existing boundaries preserved

- `adapter-jdbc` remains Farabi's persistence adapter for execution metadata, artifacts,
  dataset catalog state and related application records.
- `adapter-jdbc-source` (Maven artifact `farabi-adapter-jdbc-source`) is the separate
  module for customer analytical databases. Keep this distinct from metadata persistence.
- `adapter-dremio`, `adapter-file` and `adapter-s3` retain their existing responsibilities.
- The engine contracts contain no JDBC, Quarkus, vendor SDK or connection-pool types.

## Engine contracts

`DataSourceContracts` defines provider-neutral source descriptors, catalog objects, column
metadata and provider capabilities. `DataSourcePorts` defines replaceable persistence,
discovery, authorization and execution ports. Query execution reuses the engine-owned
`QueryContracts`; avoid introducing duplicate request, plan, parameter or submission types.

A runtime may compose different implementations behind these ports. The engine does not
create connections or store credentials. Capabilities are advisory metadata, not policy
decisions and not proof that an operation is safe.

Provider operations receive the persisted `ConnectionDefinition` on each call. This lets a provider registry hold one generic adapter per provider key while the adapter resolves endpoint-specific resources from the requested workspace-scoped source. Do not store a mutable 'current source' on a singleton provider.

## JDBC adapter

The customer-database adapter lives in the Maven module `adapter-jdbc-source`, with artifact
ID `farabi-adapter-jdbc-source`. Do not refer to it as `adapter-jdbc-datasource` unless the
module is deliberately renamed in the root reactor and all build/documentation references are
updated together.

The JDBC adapter is responsible for translating the engine's source and query contracts to
JDBC behavior. Connection pools, driver configuration and credential resolution belong to
runtime wiring; JDBC classes must not leak into engine contracts.

## Security and execution contract

The JDBC executor is a low-level adapter, not an authorization boundary. It may only be
called after Farabi has authenticated the caller, resolved trusted workspace context,
authorized the source/dataset/columns, validated a semantic query plan and applied query
limits. Do not expose this adapter as an unrestricted SQL endpoint.

Query parameters must be bound through prepared statements. Identifiers cannot be safely
bound as values; any identifier selection must come from validated catalog/semantic metadata
and dialect-aware quoting, never direct user or agent string interpolation.

Read-only connection hints do not replace least-privilege database credentials and
database-side grants. Runtime deployments must enforce TLS, secret resolution, pool limits
and outbound network controls. Driver-specific timeout, cancellation, type mapping and
metadata behavior must be verified against supported database versions.

## Current limitations and integration rules

- The JDBC source adapter is distinct from the JDBC persistence adapter.
- The Dremio adapter must converge on the common source/catalog/capability contracts without
  replacing its existing durable asynchronous query-job lifecycle.
- DremioSourceProvider now bridges the existing REST executor and job lifecycle into DataSourceContracts.Provider. Its catalog is still supplied by runtime composition; the Dremio REST catalog implementation and runtime registration remain outstanding. Dremio parameter binding is explicitly unsupported and must not be advertised.
- JDBC's synchronous execution path must be integrated with durable Farabi job management
  without weakening deadlines, cancellation, audit or workspace authorization.
- No cross-source joins or distributed federation are implemented. Use Dremio/Toba or Trino
  for federation rather than adding a federation engine to Farabi.
- A capability snapshot is advisory. The query planner and governance layer remain responsible
  for policy enforcement and safe operation selection.
- PostgreSQL/MySQL support and any additional driver matrix must be backed by real integration
  tests; do not infer compatibility solely from JDBC driver availability.

## Verification

Run the customer-database adapter tests using the module path present in the root Maven
reactor:

```bash
mvn -pl adapter-jdbc-source -am test
mvn verify
```

A successful build is not, by itself, proof of production readiness. Record whether
Testcontainers tests actually ran or were skipped because Docker was unavailable.

## Next increments

1. Verify and complete JDBC dialect behavior: identifier quoting, pagination, type
   normalization, parameter binding and cancellation.
2. Complete runtime-managed connection definitions with secret references, TLS, pool limits
   and explicit network egress controls.
3. Adapt Dremio catalog and execution capabilities to common source contracts without
   replacing its durable asynchronous job lifecycle.
4. Add durable query-job integration for synchronous JDBC execution without weakening
   deadlines, cancellation, audit or workspace authorization.
5. Publish a tested database/driver/version compatibility matrix.
