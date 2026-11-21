# Architecture decision records

- ADR-001: Java 21 pure engine and hexagonal ports — **accepted for foundation**.
- ADR-002: Quarkus is a replaceable default runtime — **accepted for foundation**.
- ADR-003: All query execution must pass governance and semantic compilation — **accepted for foundation**.
- ADR-004: Persist intent before remote submission; reconcile ambiguous outcomes — **accepted for foundation**, recovery pending.
- ADR-005: Agent-generated dashboards become immutable, versioned definitions after review — **proposed**.
- ADR-006: Toba/Tenun/Batik are adapter integrations, not core dependencies — **proposed**.
- ADR-007: Fenced workers and idempotent recovery required for production — **proposed**.
- ADR-008: Isolate agent orchestration behind a Farabi-owned boundary — **accepted for the first integration increment**; remote Andalus Runtime adapter, Farabi-owned authorization/execution, and explicit human promotion gate.
- [ADR-009: Query deadlines, cancellation, and ambiguous provider outcomes](ADR-009-query-deadlines-and-reconciliation.md) — **proposed**; durable deadlines, idempotent cancellation, and provider reconciliation.
- [ADR-010: Provider-neutral datasource contracts and parameterized query plans](ADR-010-datasource-contracts-and-parameterized-plans.md) — **accepted for the additive foundation**; existing SQL-only execution remains supported during migration.

Implementation sequencing: [Query execution reliability plan](../QUERY_EXECUTION_RELIABILITY_PLAN.md) — phased contract, migration, provider, deadline, reconciliation, and rollout work; planning only.

Each ADR records a decision at the stated scope. An accepted architecture boundary does not mean its implementation or production acceptance gates are complete.
