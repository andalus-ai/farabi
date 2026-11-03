# Multi-source data-source SPI (foundation)

## Objective

Provide a provider-neutral engine boundary for registered analytical sources while keeping
database-specific connection pools, drivers and metadata behavior in adapters. This is the
first implementation slice of the multi-source milestone; it is not yet a complete connector
management product.

## Existing boundaries preserved

- `adapter-jdbc` remains Farabi's persistence adapter for execution metadata, artifacts,
  dataset catalog state and related application records.
- `adapter-jdbc-datasource` is a separate module for customer analytical databases.
- `adapter-dremio`, `adapter-file` and `adapter-s3` retain their existing responsibilities.
- The engine contracts contain no JDBC, Quarkus, vendor SDK or connection-pool types.

## Engine contracts

`DataSourceContracts` defines source descriptors, catalog objects, column metadata,
bounded query requests and typed tabular results.

`DataSourcePorts` defines three replaceable ports:

- `Registry`: discover configured source descriptors.
- `CatalogProvider`: enumerate tables/views and their columns.
- `QueryExecutor`: execute a bounded, parameterized query against a source.

A runtime may compose different implementations behind these ports. The engine does not
create connections or store credentials.

## JDBC adapter

`adapter-jdbc-datasource` currently provides:

- `JdbcDataSourceRegistry`: immutable mapping from Farabi source IDs to application-managed
  `javax.sql.DataSource` pools.
- `JdbcCatalogProvider`: JDBC DatabaseMetaData-based table and column discovery.
- `JdbcQueryExecutor`: prepared-statement execution, bind parameters, query timeout and
  row cap with a truncation indicator.

The module intentionally does not instantiate a driver or build a pool from an arbitrary URL.
The deployment/runtime must create and lifecycle-manage pools, configure TLS, resolve secrets,
rotate credentials and apply network egress controls. Each source should use a dedicated
least-privilege database principal. The descriptor's read-only flag and JDBC
`Connection.setReadOnly(true)` are defense-in-depth hints; they do not replace read-only
database credentials and database-side grants.

## Security and execution contract

The JDBC executor is a low-level adapter, not an authorization boundary. It must only be
called after Farabi has authenticated the caller, resolved the trusted workspace context,
authorized the source/dataset/columns, validated a semantic query plan and applied query
limits. Do not expose this adapter as an unrestricted SQL endpoint.

Query parameters are bound through PreparedStatement. Identifiers cannot be safely bound as
values; any future identifier selection must come from validated catalog/semantic metadata
and dialect-aware quoting, never direct user or agent string interpolation.

The adapter caps returned rows and sets a statement timeout. Production deployments must
add database-side statement/resource limits and cancellation integration. JDBC driver
behavior differs; validate timeout, cancellation, type mapping and metadata semantics for
each supported database/version.

## Current limitations

- No public connection CRUD API or persisted connection registry yet.
- Pool provisioning, secret references, credential rotation, TLS policy and network policy
  are runtime responsibilities and still need an explicit management design.
- No dialect SPI yet. JDBC metadata and SQL behavior vary by vendor.
- The existing Dremio query lifecycle adapter has not yet been adapted to the new catalog
  SPI; Dremio remains independently wired through the existing query ports.
- No cross-source joins or distributed federation are implemented. Use Dremio/Toba or Trino
  for federation rather than adding a federation engine to Farabi.
- The JDBC query port returns synchronous tabular results. It is not yet wired into the
  existing durable asynchronous query-job lifecycle.
- Driver compatibility should be proven with integration tests for PostgreSQL, MySQL/MariaDB,
  SQL Server and Oracle before claiming support. The initial test uses H2 only.

## Verification

The adapter module includes H2 tests for metadata discovery, parameter binding, row limiting,
unknown source rejection and read-only registration enforcement. Run:

```bash
mvn -pl adapter-jdbc-datasource -am test
mvn test
```

The test suite has not been executed as part of authoring this change; CI/build evidence must
be recorded separately.

## Next increments

1. Introduce a dialect/capability SPI for identifier quoting, pagination, type mapping and
   cancellation semantics.
2. Add PostgreSQL and MySQL Testcontainers integration tests and publish a supported-driver
   matrix.
3. Add runtime-managed connection definitions with secret references, TLS and pool limits.
4. Adapt Dremio and later Trino catalog/query capabilities to the same source contracts where
   semantics genuinely align.
5. Add durable query-job integration for synchronous JDBC execution without weakening
   deadlines, cancellation, audit or workspace authorization.
