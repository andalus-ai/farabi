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
bounded query requests and typed tabular results. `DataSourceCapabilities` is an immutable,
provider-neutral snapshot of useful source/driver facts; it contains no JDBC classes.

`DataSourcePorts` defines replaceable ports:

- `Registry`: discover configured source descriptors.
- `CatalogProvider`: enumerate tables/views and their columns.
- `CapabilitiesProvider`: report capabilities of a connected source.
- `QueryExecutor`: execute a bounded, parameterized query against a source.

A runtime may compose different implementations behind these ports. The engine does not
create connections or store credentials. Capabilities are advisory metadata, not policy
decisions and not proof that an operation is safe.

## JDBC adapter

`adapter-jdbc-datasource` currently provides:

- `JdbcDataSourceRegistry`: immutable mapping from Farabi source IDs to application-managed
  `javax.sql.DataSource` pools.
- `JdbcCatalogProvider`: JDBC DatabaseMetaData-based table and column discovery.
- `JdbcCapabilitiesProvider`: reports database/driver versions, identifier quoting,
  transaction support and metadata limits from the live JDBC driver.
- `JdbcSourceRuntime` (Quarkus): exposes the registry, catalog, capability and query ports
  through CDI while owning pool shutdown.
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

## Runtime-managed connection lifecycle (initial slice)

Quarkus runtime configuration supports named entries under `farabi.sources.jdbc.<source-id>`.
Each entry requires a display name, JDBC URL, username and opaque password secret reference.
Resolved passwords are obtained through the runtime `SecretResolver` port; inline passwords
are not part of the source configuration contract. A deployment must install exactly one
secret resolver when at least one customer source is configured. With no configured sources,
the registry is empty and no resolver is required.

The runtime creates HikariCP pools, owns their lifecycle, and closes them at shutdown. Pool
size is bounded (1–50), connection timeout is bounded, and source IDs are validated before
being used in pool names. The initial runtime supports PostgreSQL and MySQL JDBC URL schemes.
Every source must require TLS: PostgreSQL uses `sslmode=verify-full`; MySQL uses
`sslMode=VERIFY_IDENTITY`. TLS cannot be disabled in this runtime slice.

Outbound hosts must be exact-match allowlisted using `farabi.sources.allowed-hosts`.
The URL cannot embed user-info. This is an application-level SSRF guard, not a replacement
for network egress controls: deployments must also restrict outbound routes and DNS/network
resolution. Never allowlist broad wildcards or public internet ranges.

Example configuration (secret reference only; no secret value):

```properties
farabi.sources.allowed-hosts=warehouse.internal,reporting.internal
farabi.sources.jdbc.warehouse.display-name=Warehouse
farabi.sources.jdbc.warehouse.url=${FARABI_WAREHOUSE_JDBC_URL}
farabi.sources.jdbc.warehouse.username=${FARABI_WAREHOUSE_DB_USER}
farabi.sources.jdbc.warehouse.password-secret-ref=${FARABI_WAREHOUSE_PASSWORD_SECRET_REF}
farabi.sources.jdbc.warehouse.read-only=true
farabi.sources.jdbc.warehouse.tls-required=true
farabi.sources.jdbc.warehouse.maximum-pool-size=10
farabi.sources.jdbc.warehouse.minimum-idle=0
farabi.sources.jdbc.warehouse.connection-timeout-ms=30000
```

The secret resolver is an integration port, not a built-in Vault/KMS implementation. Secret
rotation currently requires runtime pool recreation/restart; hot rotation and pool draining
are a later increment. TLS trust roots/client certificates are deployment/driver configuration
and require environment-specific validation.

## Current limitations

- No public connection CRUD API or persisted connection registry yet.
- Connection pools, opaque secret references, TLS enforcement and exact-host allowlisting
  are now wired in Quarkus. A concrete secret-manager plugin, hot credential rotation,
  pool draining, TLS trust-root management and deployment egress policies remain outstanding.
- The capability snapshot is not yet a complete dialect SPI: Farabi does not yet provide
  portable pagination, type normalization, cancellation or SQL-generation strategies.
- The existing Dremio query lifecycle adapter has not yet been adapted to the new source
  catalog/capability SPI; Dremio remains independently wired through the existing query ports.
- No cross-source joins or distributed federation are implemented. Use Dremio/Toba or Trino
  for federation rather than adding a federation engine to Farabi.
- The JDBC query port returns synchronous tabular results. It is not yet wired into the
  existing durable asynchronous query-job lifecycle.
- PostgreSQL and MySQL compatibility tests are present using Testcontainers, but the full
  driver matrix (MariaDB, SQL Server, Oracle and other targets) is not established.

## Verification

The adapter module includes H2 unit tests for metadata discovery, parameter binding, row
limiting, unknown source rejection, read-only registration enforcement and capability
discovery. PostgreSQL 16 and MySQL 8.4 integration tests run with Testcontainers and are
automatically skipped when Docker is unavailable.

Run:

```bash
mvn -pl adapter-jdbc-datasource -am test
mvn verify
```

These checks have not yet been executed in this authoring session; build/CI evidence must be
recorded separately.

## Next increments

1. Complete dialect behavior (identifier quoting, pagination, type normalization and
   cancellation) only where real providers need a common abstraction.
2. Add runtime-managed connection definitions with secret references, TLS and pool limits.
3. Adapt Dremio catalog and execution capabilities to the common source contracts without
   replacing its durable asynchronous job lifecycle.
4. Add durable query-job integration for synchronous JDBC execution without weakening
   deadlines, cancellation, audit or workspace authorization.
5. Publish a tested driver/version compatibility matrix.
