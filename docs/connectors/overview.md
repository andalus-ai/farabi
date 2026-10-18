# Andalus BI connector framework — P2 continuation

The connector SPI is intentionally independent from Toba, Dremio, and other external projects. It defines:

- `SourceDescriptor`: workspace-owned source metadata, connector type, opaque server-side secret reference.
- `SourceRegistry`: immutable lookup keyed by workspace and source ID.
- `SourceConnector`: source execution port and optional schema discovery.
- `ConnectorCapabilities`: feature flags and maximum result rows.
- `ConnectorRegistry`: type-based adapter lookup.
- `SourceQueryRouter`: workspace-scoped routing and connector policy checks.
- `JdbcSourceConnector`: initial adapter for provisioned JDBC DataSources.

## Execution path

```text
Authenticated actor
    -> actor-authorized semantic catalog
    -> SemanticSqlCompiler (trusted plan)
    -> SourceQueryRouter (workspace and connector checks)
    -> SourceConnector
    -> database / platform
```

## Integration roadmap

1. **Toba/Dremio adapter**: inspect the actual Toba API, dialect and supported auth/metadata methods; implement `SourceConnector` without assuming it uses JDBC.
2. **Dialect SPI**: introduce SQL compiler per connector/dialect rather than treating PostgreSQL syntax as universal.
3. **Metadata discovery**: apply workspace and object-level authorization before returning schemas.
4. **Lifecycle**: add connection health checks, credential rotation, pooling, circuit breakers, and cancellation.
5. **Observability**: audit source access, query status, latency, errors, and resource budgets.

## Security limitations

The router accepts a `QueryPlan` and **does not verify that SQL came from the semantic compiler**. Do not expose this port to untrusted clients or model outputs. Production code must require a validated plan issued by an actor-aware query service.

Workspace membership in `AccessContext` is not a substitute for row-level security, per-metric policy, or per-source authorization. Only a verified authenticator may construct this context. Use database least-privilege roles, enforce tenant predicates/row policies at the source, and resolve secrets only on the server. No arbitrary JDBC URL or raw credentials should be accepted from HTTP requests.

The JDBC adapter currently has no schema discovery, cancellation, retry/backpressure, or active health checks. No actual Toba/Dremio integration is included in this milestone.

## Verification

Unit tests cover routing, isolation, duplicate connector registration, and capability constraints. They have not been executed in this GitHub editing session. Integration tests against a configured data source are still required.
