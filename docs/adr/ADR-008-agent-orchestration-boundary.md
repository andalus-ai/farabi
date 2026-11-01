# ADR-008: Isolate agent orchestration behind a Farabi-owned boundary

- **Status:** Accepted for the first integration increment
- **Date:** 2026-11-01
- **Decision owners:** Farabi architecture
- **Related:** ADR-001, ADR-002, ADR-003, ADR-004, ADR-005, ADR-006; `docs/ECOSYSTEM_DEPENDENCIES.md`

## Context

Farabi already contains a Java 21, framework-neutral engine and a narrow proposal port: `DashboardPlanner.Proposer`. The deterministic `HeuristicProposer` is its reference implementation. The engine also owns semantic catalog views, visualization contracts, query authorization/compiler/execution ports, durable query lifecycle, and dashboard manifest/promotion contracts.

Andalus Framework and Andalus Runtime are natural collaborators for model/tool orchestration, but their inspected Maven parents target Java 25 and the framework's module versions are not yet consistent. Embedding those libraries into Farabi's Java 21 engine would couple the core to an unresolved toolchain and dependency graph. A direct runtime call also must not become a path around Farabi's authorization or query lifecycle.

The current foundation is intentionally incomplete:
- Quarkus installs deny-all authorization and an unconfigured semantic compiler by default.
- The reference semantic compiler accepts only a narrow `datasetId:measureId` grammar; it is not the complete semantic query layer.
- `DashboardPlanner.Proposer` is a port, not a production Andalus adapter or a completed natural-language workflow.
- `AgentDashboardWorkflow.publish` composes and promotes in one call; it is not an analyst review/approval workflow.
- Dashboard chart filters are represented in visualization contracts, but `DashboardRuntime` explicitly does not execute UI filters. They must not be treated as authorized query predicates.
- Durable execution still needs production-grade idempotency, fencing and reconciliation.

## Decision

1. **Keep Andalus out of `engine` and `runtime-core` for the first increment.** Integrate with Andalus Runtime over a versioned remote contract, using an adapter owned by Farabi at the runtime/composition boundary. Do not add Java 25 framework artifacts to the Java 21 build.
2. **Use `DashboardPlanner.Proposer` as the first seam.** Implement a remote proposer adapter that translates a Farabi-owned request/response DTO to the agreed Andalus Runtime protocol. Keep the deterministic proposer available for offline tests and controlled fallback.
3. **Farabi remains the policy decision and execution authority.** The agent may discover only the authorized `CatalogView` supplied by Farabi and return a structured visualization/semantic proposal. Any query execution must be a separate call through Farabi's authenticated application service, which re-authorizes, validates semantic fields and filters, applies limits, compiles, persists intent, and invokes the selected data adapter.
4. **Do not expose general-purpose agent tools.** Register only explicit Farabi capabilities such as authorized catalog discovery, proposal submission, governed query submission, and dashboard draft retrieval. Do not grant raw SQL/JDBC, shell, filesystem, unrestricted MCP, arbitrary HTTP, or direct Toba credentials.
5. **Separate proposal, review, promotion and publication.** Agent output creates a draft/proposal with provenance. A trusted analyst action, authenticated and authorized by Farabi, is required to promote it to an immutable manifest. The existing combined publish method is foundation code and must not be presented as satisfying this approval gate.
6. **Keep identity and data integrations replaceable.** A future Numou adapter may map trusted authentication/tenant context into Farabi's host identity port after public contracts and runtime compatibility are verified. Toba remains behind Farabi's execution/catalog ports. Neither integration is a dependency of the agent protocol or engine.
7. **Treat the wire protocol as a versioned contract.** Prefer an authenticated service-to-service HTTP contract initially; A2A may be used as transport/protocol only if it preserves the same explicit schemas, identity propagation, capability restrictions, deadlines, idempotency and audit semantics. Do not assume an endpoint or DTO from repository names; agree and test the actual Runtime API before implementation.

## First-increment contract

The initial remote operation is proposal-only:

- **Request:** contract version; request/correlation ID; prompt; locale/timezone if relevant; a server-derived, authorized catalog snapshot (dataset IDs, descriptions, measures, dimensions and semantic revision); bounded output constraints; and an allowlisted capability profile.
- **Identity:** authenticated service identity is carried by the transport. The authoritative workspace and subject are resolved by Farabi at its API boundary. Any delegated actor/workspace context is signed or otherwise cryptographically bound by the trusted host; it is never accepted from prompt text or model-generated tool arguments.
- **Response:** structured proposal containing chart/visualization specifications, semantic references, assumptions, and provenance/model metadata. No executable SQL, arbitrary URLs, code, credentials or claims of authorization.
- **Validation:** Farabi validates schema, size/count limits, dataset and field membership, chart bindings, filters, layout and semantic revision against its own catalog/policy. Unknown, stale or unauthorized references are rejected. Rejected proposals cannot trigger data-source calls.
- **Execution:** only Farabi's governed query application path can execute. The agent receives a bounded result projection only after server authorization and execution complete.
- **Approval:** promotion requires a separate authenticated analyst action referencing the exact draft/proposal digest and revision. Any changed proposal requires a new review.
- **Reliability:** propagate correlation and idempotency keys; set deadlines and bounded retries; distinguish definitive rejection from ambiguous timeout; do not retry a potentially accepted side effect without reconciliation.
- **Audit:** record caller/service identity, workspace, subject, correlation ID, agent/runtime version, model/provider where available, tool/capability invoked, proposal digest, policy decision, query execution ID, approval actor and promoted manifest revision. Avoid storing secrets or unnecessary prompt/result data.

## Consequences

### Positive

- Farabi's Java 21 engine remains portable and independently testable.
- Andalus Runtime can evolve its JDK, providers and orchestration internals without forcing Farabi's core to follow.
- Agent behavior is replaceable; deterministic tests do not require a model service.
- A model cannot elevate privileges by inventing a tenant, dataset, field, SQL statement or tool call.
- Numou, Toba, Batik and Tenun remain independently versioned ecosystem products.

### Costs and trade-offs

- A remote adapter adds network latency, authentication, contract versioning and operational dependency management.
- The service contract and identity delegation model must be agreed with the Andalus Runtime maintainers before coding.
- Proposal validation, review state and durable audit require additional Farabi work; existing foundation classes are not a substitute.

## Acceptance gates before implementation is considered integrated

- [ ] Confirm Andalus Runtime's supported API/protocol, authentication model, deployment contract and supported JDK.
- [ ] Agree and version proposal request/response schemas; add compatibility and malformed-payload tests.
- [ ] Demonstrate that workspace/subject are server-derived and cannot be overridden by prompt or tool arguments.
- [ ] Prove unauthorized dataset/field/filter proposals are rejected before any Toba/JDBC call.
- [ ] Prove no agent capability can submit arbitrary SQL or invoke unregistered tools.
- [ ] Add approval tests proving only the reviewed proposal digest/revision can be promoted.
- [ ] Add timeout, duplicate request, ambiguous acceptance and audit-correlation tests.
- [ ] Verify real adapter behavior against a running Andalus Runtime; mocks alone do not satisfy integration.
- [ ] Resolve Java/BOM/version alignment before considering in-process Andalus Framework dependencies.

Until these gates pass, describe the integration as **designed** or **partial**, not production-ready.
