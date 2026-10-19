# Andalus BI roadmap

## P0 — domain foundation (this branch)
- [x] Repository bootstrap and Java build.
- [x] Semantic metric contract and aggregation enum.
- [x] Dashboard/widget artifact contract.
- [x] Deterministic validation and unit tests.
- [ ] CI build and repository-specific integration verification.

## P1 — persistence and API
- Add repository ports and PostgreSQL implementation/migrations.
- Add tenant/workspace authorization context at every application boundary.
- Add dashboard CRUD, immutable version history, and audit events.
- Add API contract and integration tests.

## P2 — query execution
- Add semantic query AST, validation, planner, and execution interface.
- Add connector SPI; integrate Toba/Dremio and additional sources through adapters.
- Add query limits, cancellation, timeouts, parameter binding, and result provenance.

## P3 — dashboard and AI authoring
- Add Tenun renderer adapter and Batik/A2UI adapter after confirming public APIs.
- Add prompt-to-semantic-query flow with structured output and validation.
- Promote dynamic outputs into static versioned dashboard artifacts.
- Add preview, diff, approval, and rollback UX/API.

## P4 — governance and real-time analytics
- Add trust/freshness metadata, lineage, and evidence-backed insights.
- Add streaming sources, event-time windows, checkpoints, and incremental result protocol.
- Add event-triggered workflows with idempotency, limits, audit, and human approval gates.

## Definition of done for each milestone
- Code compiles on the declared JDK.
- Unit tests cover normal and invalid inputs.
- External side effects are behind interfaces.
- API and persisted formats are versioned.
- Security, tenant isolation, failure handling, and operational visibility are documented.
