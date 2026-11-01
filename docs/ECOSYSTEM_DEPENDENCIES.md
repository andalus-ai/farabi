# Ecosystem Dependencies and Integration Boundaries

**Status:** Architecture proposal and dependency register. This document records intended product relationships; it does not claim that every integration is implemented, released, or production-ready.

## 1. Product dependency map

Farabi is the governed analytics product. It composes independent Andalus ecosystem products through adapters and versioned contracts.

| Product/package | Repository or documentation | Farabi relationship | Ownership boundary |
| --- | --- | --- | --- |
| Numou | https://github.com/andalus-ai/numu | Reuse compatible identity, tenancy, security, and shared platform capabilities | Numou owns reusable business/platform capabilities; Farabi owns BI-specific governance and analytics |
| Toba | https://github.com/andalus-ai/toba | Preferred native lakehouse/catalog/query integration, behind Farabi data-source ports | Toba remains an independent data/lakehouse platform; Farabi remains engine-neutral |
| Batik | https://github.com/andalus-ai/batik | Flutter agent-driven UI / A2UI presentation adapter | Batik owns UI schema rendering and interaction mechanics; Farabi owns authorization and canonical dashboard artifacts |
| Tenun | https://pub.dev/documentation/tenun/latest/ | Chart rendering integration | Tenun owns chart rendering; Farabi owns semantic meaning, data authorization, and chart specifications |
| Tenun Core | https://pub.dev/documentation/tenun_core/latest/ | Candidate core chart contracts/utilities | Use only the public package API and only where needed |
| Tenun Pro | https://pub.dev/documentation/tenun_pro/latest/ | Optional advanced chart capability | Optional presentation dependency; avoid coupling the engine to it |
| Tenun 3D Core | https://pub.dev/documentation/tenun_3d_core/latest/ | Optional 3D chart capability | Separate optional visualization capability; not a default Farabi dependency |

The Tenun packages are Dart/Flutter-side dependencies, not Java/Maven dependencies. Keep them outside Farabi's Java engine and server runtime. Pin compatible versions in the Flutter application's package manifest after API and license review.

## 2. Numou reuse: first integration candidates

The Numou repository currently exposes separate capability reactor roots and public module boundaries. The following Maven coordinates were observed in its current POM files (all at `0.1.0-SNAPSHOT` in the inspected source):

| Capability | Candidate artifact | Intended use in Farabi | Integration status |
| --- | --- | --- | --- |
| Identity | `tech.kayys.numou:numou-identity-domain` | Identity domain types only where cross-product semantics genuinely match | Candidate; inspect API and tests before adoption |
| Identity | `tech.kayys.numou:numou-identity-application` | Public identity use cases/ports | Candidate; preferred over importing adapter internals |
| Tenancy | `tech.kayys.numou:numou-tenancy-domain` | Tenant and membership domain concepts | Candidate |
| Tenancy | `tech.kayys.numou:numou-tenancy-spi` | Tenant repository/provisioner contracts | Candidate |
| Tenancy | `tech.kayys.numou:numou-tenancy-application` | Tenant lifecycle and application use cases | Candidate |
| Security | `tech.kayys.numou:numou-security-spi` | Authentication, policy, and audit ports | Candidate; verify contract semantics and compatibility |

Repository: https://github.com/andalus-ai/numu

**Important constraints**

- These are source-repository coordinates, not a promise that artifacts are published to Maven Central or another configured repository. Both products currently use snapshot versions; establish a reproducible release or source-build strategy before adding them to Farabi's POM.
- Numou documents multiple Maven reactor roots; do not assume a root-level build. Build and test the relevant capability reactor and its required framework dependencies.
- Prefer Numou's documented public domain/application/SPI contracts. Do not depend on its private persistence models, internal implementation packages, or database tables.
- The identity adapter POM itself records that it was authored outside a network-connected build environment and has not been compiled against its real Quarkus BOM. The tenancy adapter currently describes in-memory implementations for development and tests. Do not treat either adapter as a production-ready Farabi integration without verification.
- Do not add every Numou module transitively to Farabi. Select the smallest compatible set and isolate it behind Farabi-owned ports/adapters.

## 3. Ownership and authority

| Concern | Authority |
| --- | --- |
| User identity and reusable identity lifecycle | Numou, when its contract is selected and verified |
| Tenant lifecycle and reusable membership | Numou, when its contract is selected and verified |
| Authentication context at the Farabi boundary | Farabi runtime validates the trusted principal and maps it into Farabi application context; never trust client-supplied subject or tenant IDs |
| BI workspace access, dataset/column/row permissions, query limits | Farabi |
| Semantic catalog, metrics, query planning and query policy | Farabi |
| Lakehouse catalog, source connectivity and query execution | Toba through a Farabi adapter; other engines remain possible |
| Dynamic agent UI and UI interaction schema | Batik through a Farabi presentation adapter |
| Chart rendering | Tenun through the Flutter presentation layer |
| Dashboard manifest, provenance, revision, review and publication | Farabi |

### Avoid competing identity authorities

Toba currently has an `identity-access-service` and a `governance-lineage-service` in its own platform architecture. That does not automatically make Toba a second identity provider for Farabi. Agree one authoritative identity/tenant source for the deployment. Toba may enforce its own service boundary and consume trusted, mapped identity context from the integration contract. Do not create inconsistent user, tenant, or permission truth across Numou, Toba, and Farabi.

Farabi's BI-specific authorization remains Farabi-owned even if generic identity, tenancy, or policy primitives are reused from Numou. A generic permission check is not a substitute for dataset, column, row, query-plan, or dashboard-publication authorization.

## 4. Dependency direction

```text
                  Numou
       identity / tenancy / shared
          capability contracts
                   |
                   | stable public contracts
                   v
Batik <----> Farabi application / engine <----> Toba
  |          semantic + BI governance          |
  |          jobs + dashboard lifecycle        |
  |                                             |
  v                                             v
Tenun                                      data engines
Flutter presentation                       and lakehouse

Farabi engine must not import Flutter, Batik, Tenun, Quarkus,
JDBC drivers, or Toba implementation classes.
```

- Farabi engine defines its own ports and stable domain contracts.
- Adapters translate Numou, Toba, Batik, and Tenun contracts at product boundaries.
- No reverse dependency from Numou, Toba, Batik, or Tenun into Farabi.
- No shared database tables or cross-product writes into another product's persistence model.
- Network boundaries use versioned APIs/events with tenant, actor, correlation, and idempotency context where relevant.
- In-process composition is acceptable when contracts remain public and deployment-neutral; do not introduce microservices solely to satisfy a diagram.

## 5. Proposed Farabi integration modules

These are target module boundaries, not a claim that all directories already exist:

```text
farabi/
  engine/                  # Java 21 domain, application use cases, ports
  runtime-core/             # framework-neutral composition
  runtime-quarkus/          # HTTP, auth integration, deployment wiring
  adapter-numou/            # identity/tenant/security contract translation
  adapter-toba/             # catalog/query/data-source integration
  adapter-batik/            # A2UI contract and delivery integration
  flutter/
    packages/
      farabi_dashboard/     # Farabi Flutter app/presentation composition
      farabi_tenun/         # Tenun chart adapter
      farabi_batik/         # Batik UI adapter
```

Keep the current repository modules working while introducing new adapters incrementally. Do not rename or split modules merely to match this proposed tree.

## 6. Integration sequence and acceptance gates

1. **Baseline:** inspect current default branches, build files, public contracts, tests, and CI for each product.
2. **Numou contract proof:** select the smallest identity/tenancy/SPI set; compile it from a reproducible source/release; prove trusted tenant and actor context mapping.
3. **Farabi governed query slice:** authenticated principal → trusted tenant context → BI dataset authorization → semantic plan → Toba (or existing adapter) execution → audited typed result.
4. **Presentation slice:** render an authorized chart through Tenun and a dynamic agent view through Batik; verify the UI cannot grant itself permissions or change trusted tenant context.
5. **Promotion slice:** review the dynamic result and promote it to Farabi's immutable, versioned dashboard manifest; reopen it in the conventional editor without losing filters, bindings, layout, or provenance.
6. **Hardening:** negative cross-tenant and denied-dataset tests, idempotency/retry behavior, compatibility tests, observability, and end-to-end CI.

A stage is complete only with code, real contract compatibility, tests, and observed CI evidence. A dependency listed here is an architectural candidate until that gate passes.

## 7. Dependency policy

- Prefer released, versioned artifacts over floating branches or snapshots for production.
- If a source build is temporarily required, pin the exact commit and document the reproducible build.
- Use Maven dependencies only for Java modules; use Pub dependencies only for Flutter/Dart packages.
- Pin compatible Tenun package versions together after checking their package constraints and actual APIs.
- Keep optional visualization packages optional; do not make 3D/pro chart packages mandatory for core Farabi.
- Review license, transitive dependencies, security advisories, supported SDK/JDK versions, and release cadence before adopting any external package.
- Record exact versions and verification evidence in the relevant module README and CI.

## 8. Upstream references

- [Farabi](https://github.com/andalus-ai/farabi)
- [Numou](https://github.com/andalus-ai/numu)
- [Toba](https://github.com/andalus-ai/toba)
- [Batik](https://github.com/andalus-ai/batik)
- [Tenun](https://pub.dev/documentation/tenun/latest/)
- [Tenun Core](https://pub.dev/documentation/tenun_core/latest/)
- [Tenun Pro](https://pub.dev/documentation/tenun_pro/latest/)
- [Tenun 3D Core](https://pub.dev/documentation/tenun_3d_core/latest/)
