# Semantic query engine — P2 foundation

This module implements a constrained, deterministic semantic query path:

```text
SemanticQuery
    -> workspace-authorized SemanticCatalog
    -> SemanticSqlCompiler
    -> QueryPlan (SQL + bind values)
    -> QueryExecutor
    -> JDBC read-only DataSource
```

## Supported in this slice

- Governed metric IDs and dimension IDs.
- Aggregations: SUM, COUNT, COUNT_DISTINCT, MIN, MAX, AVG.
- Equality and inequality scalar filters on governed dimensions.
- Grouping by selected dimensions.
- Prepared-statement bind parameters and a bounded result limit.
- JDBC query execution with statement timeout and maximum row count.
- Unit tests for compilation, identifier rejection and bound values.

## Important boundaries

The compiler currently targets PostgreSQL-compatible SQL only. Toba/Dremio needs its own dialect and connector implementation; none is claimed here.

A trusted catalog must be assembled from a verified workspace/actor authorization context. The current API does not yet wire that authorization into query execution, and a read-only JDBC connection flag alone is **not** a database security boundary. Configure a least-privileged database role, row-level access policies, resource quotas and source-specific timeout controls before deployment.

Do not pass raw SQL, user-defined table names, or unverified AI output to the executor. A future query service must validate catalog access and enforce per-user data policies before compilation.

Not yet implemented: semantic joins, expression AST, date bucketing, pagination, cancellation, caching, query lineage, tenant row filters, source dialect negotiation, or real Toba/Dremio integration.

## Verification

Unit tests are committed but not yet executed by this editing session. Add CI and integration tests against an isolated PostgreSQL instance before merge.
