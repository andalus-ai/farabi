# Farabi

**Farabi** is a library-first, governed, agentic business intelligence platform. It combines conventional dashboards, semantic query execution, AI-assisted analytics, and promotion of dynamic results to durable, versioned dashboard artifacts.

## Principles
- **Hexagonal Java core**: no Quarkus, JDBC, HTTP, or vendor SDK dependencies in the engine.
- **Replaceable adapters**: Toba/Dremio for data access, Tenun for charts, Batik for A2UI, plus other providers.
- **Governance before execution**: authorization, semantic compilation, source validation, then execution.
- **Dynamic → static**: AI-generated visualizations are reviewable drafts that can become versioned dashboards.
- **Reliable operations**: durable jobs, idempotency, reconciliation, audit, workspace isolation.

## Modules
`engine` — domain, ports and application use cases.
`runtime-core` — framework-neutral composition.
`adapter-jdbc` — PostgreSQL-compatible execution metadata persistence.
`adapter-dremio` — Dremio REST query submission.
`runtime-quarkus` — optional Quarkus deployment.

## Build
Requires Java 21 and Maven 3.9+: `mvn verify`.

## Project status
Foundation implementation only. Production identity/governance adapters, complete semantic compilation, worker fencing, AI generation, Tenun/Batik UI and dynamic-to-static promotion are roadmap work. See [architecture](docs/architecture.md), [vision](docs/vision.md), [roadmap](docs/roadmap.md) and [ADRs](docs/adr/README.md). Do not expose the query executor as a public ungoverned SQL endpoint.
