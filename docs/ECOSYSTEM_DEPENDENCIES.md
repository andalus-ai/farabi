# Ecosystem Dependencies and Integration Boundaries

**Status:** Architecture proposal and dependency register. This records intended relationships and candidate integrations; it does not claim that every integration is implemented, released, or production-ready.

## 1. Product dependency map

Farabi is the governed analytics product. It composes independent Andalus ecosystem products through adapters and versioned contracts.

| Product/package | Repository or documentation | Farabi relationship | Ownership boundary |
| --- | --- | --- | --- |
| Numou | https://github.com/andalus-ai/numu | Reuse compatible identity, tenancy, security, and shared platform capabilities | Numou owns reusable business/platform capabilities; Farabi owns BI-specific governance and analytics |
| Andalus Framework | https://github.com/andalus-ai/andalus-framework | Reuse selected agent framework contracts and capabilities through a Farabi-owned agent adapter | Framework owns reusable agent abstractions, SPIs, tools, provider, memory, security and governance contracts |
| Andalus Runtime | https://github.com/andalus-ai/andalus | Candidate execution/orchestration runtime for agent workflows; preferably isolated as a separately deployed runtime initially | Runtime owns agent execution, orchestration, deployment, runtime integrations and operational lifecycle; Farabi owns analytics intent, BI policy and approved outcomes |
| Toba | https://github.com/andalus-ai/toba | Preferred native lakehouse/catalog/query integration, behind Farabi data-source ports | Toba remains an independent data/lakehouse platform; Farabi remains engine-neutral |
| Batik | https://github.com/andalus-ai/batik | Flutter agent-driven UI / A2UI presentation adapter | Batik owns UI schema rendering and interaction mechanics; Farabi owns authorization and canonical dashboard artifacts |
| Tenun | https://pub.dev/documentation/tenun/latest/ | Chart rendering integration | Tenun owns chart rendering; Farabi owns semantic meaning, data authorization, and chart specifications |
| Tenun Core | https://pub.dev/documentation/tenun_core/latest/ | Candidate core chart contracts/utilities | Use only the public package API and only where needed |
| Tenun Pro | https://pub.dev/documentation/tenun_pro/latest/ | Optional advanced chart capability | Optional presentation dependency; avoid coupling the engine to it |
| Tenun 3D Core | https://pub.dev/documentation/tenun_3d_core/latest/ | Optional 3D chart capability | Separate optional visualization capability; not a default Farabi dependency |

Tenun packages are Dart/Flutter-side dependencies, not Java/Maven dependencies. Keep them outside Farabi's Java engine and server runtime. Pin compatible versions in the Flutter application's package manifest after API and license review.

## 2. Numou reuse: first integration candidates

The Numou repository currently exposes separate capability reactor roots and public module boundaries. The following Maven coordinates were observed in its POM files (the inspected capability modules declare `0.1.0-SNAPSHOT`):

| Capability | Candidate artifact | Intended use in Farabi | Integration status |
| --- | --- | --- | --- |
| Identity | `tech.kayys.numou:numou-identity-domain` | Identity domain types only where cross-product semantics genuinely match | Candidate; inspect API and tests before adoption |
| Identity | `tech.kayys.numou:numou-identity-application` | Public identity use cases/ports | Candidate; preferred over importing adapter internals |
| Tenancy | `tech.kayys.numou:numou-tenancy-domain` | Tenant and membership domain concepts | Candidate |
| Tenancy | `tech.kayys.numou:numou-tenancy-spi` | Tenant repository/provisioner contracts | Candidate |
| Tenancy | `tech.kayys.numou:numou-tenancy-application` | Tenant lifecycle and application use cases | Candidate |
| Security | `tech.kayys.numou:numou-security-spi` | Authentication, policy, and audit ports | Candidate; verify contract semantics and compatibility |

Repository: https://github.com/andalus-ai/numu

**Constraints:** these are source-repository coordinates, not a promise that artifacts are published to Maven Central or another configured repository. Numou documents multiple Maven reactor roots; do not assume a root-level build. Prefer public domain/application/SPI contracts, never private persistence models or tables. The inspected identity adapter POM records that it has not been compiled against its real Quarkus BOM, and the tenancy adapter describes in-memory implementations for development/tests. Verify before production use. Select the smallest compatible set and isolate it behind Farabi-owned ports/adapters.

## 3. Andalus agent integration

The two repositories have distinct roles:

- **Andalus Framework** is the reusable library layer: agent contracts/orchestration, execution and workflow abstractions, providers, tools, memory/context, RAG/knowledge, communication protocols (including A2A/ANP), security APIs, guardrails, HITL and audit/observability capabilities.
- **Andalus Runtime** is the executable runtime layer: agent execution, multi-agent/workflow orchestration, backend integrations, runtime guardrails/HITL, APIs/CLI and deployment/operations.

### Candidate framework artifacts

The framework parent POM identifies `tech.kayys.andalus:andalus-framework-build:0.0.1`. Inspected module POMs include the following artifact IDs:

| Candidate artifact | Potential Farabi use | Notes |
| --- | --- | --- |
| `tech.kayys.andalus:andalus-core` | Agent request/context and core contracts | Candidate; inspect API compatibility |
| `tech.kayys.andalus:andalus-agent` | Agent abstractions/orchestration | Candidate; avoid importing implementation unnecessarily |
| `tech.kayys.andalus:andalus-provider` | Model/provider abstraction | Candidate; keep provider choice configurable |
| `tech.kayys.andalus:andalus-tool` | Tool invocation contracts | Candidate; tools must be capability-allowlisted |
| `tech.kayys.andalus:andalus-security-api` | Security and delegation contracts | Candidate; reconcile with Numou identity authority and Farabi BI policy |
| `tech.kayys.andalus:andalus-guardrails` | Agent input/output safeguards | Candidate; inspect dependency graph and test readiness |
| `tech.kayys.andalus:andalus-audit` | Agent activity/audit integration | Candidate; map to Farabi audit event contract, avoid competing stores |

These are candidate artifacts, not an approved dependency BOM. The framework POMs inspected show inconsistent version declarations: parent `0.0.1`, while some modules override their own version (for example `andalus-audit` declares `1.0.0-SNAPSHOT`, and `andalus-guardrails` declares `1.0.0-SNAPSHOT` while depending on `0.0.1` artifacts). Resolve and publish a consistent BOM before adding direct Maven dependencies.

### Runtime compatibility is a gating issue

The current Farabi README targets Java 21. The inspected Andalus Framework and Runtime parent POMs set Java source/target 25; the framework parent also imports Quarkus 3.32.2. Therefore, **do not add these Java artifacts directly to Farabi's Java 21 build yet**. First either:

1. align and validate the ecosystem on a common supported JDK/BOM, or
2. run Andalus Runtime as a separately deployed agent service and integrate it through a versioned HTTP/gRPC/A2A-style contract.

The second option is the safer initial boundary: Farabi remains deployable independently and does not inherit the agent runtime's transitive dependency graph. Revisit in-process embedding only after JDK, dependency, lifecycle, security-context and test compatibility are demonstrated.

### Farabi remains the policy authority for analytics

Andalus agents may propose plans, compose workflows, call approved tools, summarize results and generate visualization drafts. They must not bypass Farabi's semantic compiler, dataset/column/row permissions, query budgets, tenant isolation, approval rules or dashboard publication lifecycle.

Every agent invocation should receive a server-validated context containing tenant, actor, correlation/request ID, allowed capability/tool set and policy constraints. Never trust tenant or actor values supplied only in a prompt or tool argument. Tool execution should call Farabi application use cases or narrow adapters; do not expose raw SQL, unrestricted JDBC, arbitrary shell, filesystem or unrestricted MCP tools to an agent.

Human approval and guardrails complement, but do not replace, deterministic authorization before data access and before publishing dashboard artifacts.

## 4. Ownership and authority

| Concern | Authority |
| --- | --- |
| User identity and reusable identity lifecycle | Numou, when its contract is selected and verified |
| Tenant lifecycle and reusable membership | Numou, when its contract is selected and verified |
| Agent abstractions, model routing, tools and agent orchestration | Andalus Framework / Runtime, through the selected integration boundary |
| Agent safety, human review and agent execution audit | Andalus capabilities may provide reusable mechanisms; Farabi maps outcomes into its own policy and audit contracts |
| Authentication context at the Farabi boundary | Farabi runtime validates the trusted principal and maps it into application context; never trust client-supplied subject or tenant IDs |
| BI workspace access, dataset/column/row permissions, query limits | Farabi |
| Semantic catalog, metrics, query planning and query policy | Farabi |
| Lakehouse catalog, source connectivity and query execution | Toba through a Farabi adapter; other engines remain possible |
| Dynamic agent UI and UI interaction schema | Batik through a Farabi presentation adapter |
| Chart rendering | Tenun through the Flutter presentation layer |
| Dashboard manifest, provenance, revision, review and publication | Farabi |

### Avoid competing identity and policy authorities

Numou is the preferred reusable identity/tenancy authority where adopted. Andalus Framework contains security APIs and policy abstractions; these must be mapped to the trusted identity context rather than becoming a second user/tenant directory. Toba also has identity/governance components in its own platform architecture; that does not automatically make it another identity provider for Farabi.

Agree one authoritative identity/tenant source per deployment. Farabi retains BI-specific authorization even if generic identity, tenancy or agent guardrail primitives are reused. A generic permission check is not a substitute for dataset, column, row, query-plan or dashboard-publication authorization.

## 5. Dependency direction

```text
Numou --------------------> Farabi-owned identity/tenant adapter
Andalus Framework -------> Farabi-owned agent adapter
Andalus Runtime <--------> Farabi agent-service contract (optional remote boundary)
Toba --------------------> Farabi data-source adapter
Batik <------------------> Farabi presentation contract
Tenun -------------------> Flutter chart adapter

             Farabi application / engine
       semantic + BI governance + jobs + dashboards
```

- Farabi engine defines its own ports and stable domain contracts.
- Adapters translate ecosystem contracts at product boundaries.
- No reverse dependency from Numou, Andalus, Toba, Batik or Tenun into Farabi.
- No shared database tables or cross-product writes into another product's persistence model.
- Network boundaries use versioned APIs/events with tenant, actor, correlation and idempotency context where relevant.
- In-process composition is acceptable when contracts remain public and deployment-neutral; do not introduce microservices solely to satisfy a diagram.

## 6. Proposed Farabi integration modules

These are target boundaries, not a claim that all directories already exist:

```text
farabi/
  engine/                  # Java 21 domain, application use cases, ports
  runtime-core/             # framework-neutral composition
  runtime-quarkus/          # HTTP, auth integration, deployment wiring
  adapter-numou/            # identity/tenant/security contract translation
  adapter-andalus/          # framework/runtime agent integration
  adapter-toba/             # catalog/query/data-source integration
  adapter-batik/            # A2UI contract and delivery integration
  flutter/
    packages/
      farabi_dashboard/     # Flutter app/presentation composition
      farabi_tenun/         # Tenun chart adapter
      farabi_batik/         # Batik UI adapter
```

Keep current repository modules working while introducing adapters incrementally. Do not rename or split modules merely to match this proposed tree.

## 7. Integration sequence and acceptance gates

1. **Baseline:** inspect current default branches, build files, public contracts, tests and CI for each product.
2. **Numou contract proof:** compile the smallest identity/tenancy/SPI set from a reproducible source/release; prove trusted tenant and actor context mapping.
3. **Agent contract proof:** select minimal Andalus Framework APIs and prove a Farabi agent invocation with server-derived tenant/actor context, restricted tool registry, timeout/cancellation, bounded token/cost budget and trace/audit correlation. Start with remote Runtime integration unless JDK compatibility is resolved.
4. **Governed query slice:** authenticated principal → trusted tenant context → BI dataset authorization → semantic plan → Toba (or existing adapter) execution → audited typed result.
5. **Presentation slice:** render an authorized chart through Tenun and a dynamic agent view through Batik; verify the UI cannot grant itself permissions or change trusted tenant context.
6. **Promotion slice:** review the dynamic result and promote it to Farabi's immutable, versioned dashboard manifest; reopen it in the conventional editor without losing filters, bindings, layout or provenance.
7. **Hardening:** negative cross-tenant and denied-dataset tests, prompt/tool-injection tests, idempotency/retry behavior, compatibility tests, observability and end-to-end CI.

A stage is complete only with code, real contract compatibility, tests and observed CI evidence. A dependency listed here is an architectural candidate until that gate passes.

## 8. Dependency policy

- Prefer released, versioned artifacts over floating branches or snapshots for production.
- If a source build is temporarily required, pin the exact commit and document the reproducible build.
- Use Maven dependencies only for Java modules; use Pub dependencies only for Flutter/Dart packages.
- Pin compatible Tenun package versions together after checking package constraints and actual APIs.
- Keep optional visualization packages optional; do not make 3D/pro chart packages mandatory for core Farabi.
- Review license, transitive dependencies, security advisories, supported SDK/JDK versions and release cadence before adoption.
- Record exact versions and verification evidence in the relevant module README and CI.

## 9. Upstream references

- [Farabi](https://github.com/andalus-ai/farabi)
- [Numou](https://github.com/andalus-ai/numu)
- [Andalus Framework](https://github.com/andalus-ai/andalus-framework)
- [Andalus Runtime](https://github.com/andalus-ai/andalus)
- [Toba](https://github.com/andalus-ai/toba)
- [Batik](https://github.com/andalus-ai/batik)
- [Tenun](https://pub.dev/documentation/tenun/latest/)
- [Tenun Core](https://pub.dev/documentation/tenun_core/latest/)
- [Tenun Pro](https://pub.dev/documentation/tenun_pro/latest/)
- [Tenun 3D Core](https://pub.dev/documentation/tenun_3d_core/latest/)
