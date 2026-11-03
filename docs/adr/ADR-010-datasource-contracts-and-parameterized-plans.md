# ADR-010: Provider-neutral datasource contracts and parameterized query plans

- **Status:** Accepted
- **Date:** 2026-11-03
- **Decision owners:** Farabi maintainers

## Context

Farabi already has a durable query lifecycle and a structured semantic compiler used by the live dashboard path. It also has source-specific adapters. The next connector must not introduce a second governance system, a second remote-job lifecycle, or vendor-specific dependencies into the Java 21 engine.

The current SQL-only `QueryContracts.Plan` and compiler output are retained by existing execution paths. The structured compiler validates filter values, but its legacy output renders those values into SQL text. New connectors need a typed parameterized contract without forcing an immediate migration of all existing adapters.

## Decision

1. Keep semantic authorization, trusted dataset metadata, query limits, and query compilation inside Farabi's engine.
2. Add provider-neutral datasource contracts for stable source identity, declared capabilities, catalog discovery, health, execution, and remote lifecycle.
3. Keep datasource connection configuration and credentials in runtime/adapters. Never put credentials or JDBC/vendor types in engine contracts.
4. Reuse `QueryLifecycle.Provider` for remote status, result paging, and cancellation. Do not create a second job lifecycle in datasource adapters.
5. Add `ParameterizedPlan` and a parameterized executor port additively. Keep the existing SQL-only `Plan` and executor port during migration.
6. Use a `SqlDialect` port for trusted identifier quoting, date bucketing, and bounded pagination syntax.
7. Treat dialect implementations and datasource configuration as trusted server-side components. Agents provide semantic intent and typed values, never raw SQL or SQL identifiers.
8. Keep existing dataset authorization and durable persist-before-submit behavior intact while the new contracts are adopted incrementally.

## Consequences

### Positive

- New source adapters can expose catalog metadata and capabilities without coupling the engine to a driver or vendor SDK.
- Filter values in the new query path are bound as typed parameters rather than interpolated into SQL.
- Dremio and existing dashboard execution can migrate incrementally.
- Farabi retains one governance boundary and one durable remote execution lifecycle.

### Trade-offs and follow-up

- Existing adapters may continue using the legacy SQL-only plan until migrated.
- The new parameterized compiler method is additive and is not yet the default execution path.
- Dialect behavior must be implemented and integration-tested per real datasource; the initial ANSI-style dialect only preserves current SQL behavior.
- A future migration must connect parameterized plans to the durable query application service before declaring generic datasource execution complete.
- Provider capability declarations must be checked by the application service; declarations alone are not a security boundary.

## Implementation sequence

- **B1.1-A:** engine datasource contracts and invariant tests.
- **B1.1-B:** typed parameterized plan, executor port, and dialect-aware compiler path.
- **B1.2:** PostgreSQL datasource adapter with real integration tests.
- **B1.3:** migrate Dremio through the same datasource contracts and preserve durable lifecycle semantics.
