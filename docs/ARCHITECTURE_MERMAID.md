# Farabi Architecture Blueprints

> Target architecture, not a claim that every component is implemented. Farabi unifies conventional and agentic BI around governed analytics and durable dashboard manifests.

## System context

```mermaid
flowchart LR
  User["Analyst / Developer"] --> Studio["Farabi Workspace"]
  Studio --> Conventional["Traditional BI Studio"]
  Studio --> Agentic["Agentic BI Studio"]
  Conventional --> Core["Farabi Engine"]
  Agentic --> Andalus["Andalus Agent Orchestration"]
  Andalus --> Core
  Core --> Toba["Toba / Dremio Adapter"]
  Core --> Other["Other Data Adapters"]
  Core --> Manifest["Versioned Manifest Store"]
  Agentic --> Batik["Batik A2UI"]
  Conventional --> Tenun["Tenun Charts"]
  Batik --> Tenun
```

## Hexagonal component boundaries

```mermaid
flowchart TB
  subgraph Clients["Presentation and Clients"]
    Builder["Conventional Builder"]
    A2UI["Batik Agent UI"]
  end
  subgraph Runtime["Replaceable Runtime"]
    API["Quarkus HTTP/API"]
    Workers["Jobs / Scheduling"]
  end
  subgraph Engine["Framework-independent Farabi Engine"]
    Semantic["Semantic Planning"]
    Governance["Authorization / Policy Ports"]
    Queries["Query Lifecycle"]
    Dashboards["Dashboard Specification"]
    Promotion["Manifest Promotion"]
  end
  subgraph Adapters["Replaceable Adapters"]
    Toba["Toba / Dremio"]
    JDBC["JDBC / Other Engines"]
    Store["Manifest Persistence"]
    Identity["Numou Identity / Tenancy"]
  end
  Builder --> API
  A2UI --> API
  API --> Semantic
  API --> Dashboards
  Workers --> Queries
  Semantic --> Governance
  Governance --> Queries
  Dashboards --> Promotion
  Queries --> Toba
  Queries --> JDBC
  Promotion --> Store
  Governance --> Identity
```

## Governed prompt-to-dashboard sequence

```mermaid
sequenceDiagram
  actor User
  participant UI as Agentic Workspace
  participant Agent as Andalus Agent
  participant Engine as Farabi Engine
  participant Policy as Governance
  participant Source as Data Adapter
  participant Batik as Batik A2UI
  User->>UI: Ask an analytics question
  UI->>Agent: Request structured analytics plan
  Agent-->>Engine: Proposed semantic plan
  Engine->>Policy: Check identity, workspace, dataset, columns and limits
  alt Denied
    Policy-->>UI: Policy denial
  else Authorized
    Policy-->>Engine: Authorized constraints
    Engine->>Source: Execute governed query
    Source-->>Engine: Typed rows and provenance
    Engine-->>Batik: Validated dashboard delivery
    Batik-->>User: Interactive dynamic dashboard
  end
```

## Dynamic-to-static artifact lifecycle

```mermaid
stateDiagram-v2
  [*] --> Proposed
  Proposed --> Validated: Schema and policy checks
  Proposed --> Rejected: Invalid or denied
  Validated --> Dynamic: Render preview
  Dynamic --> Reviewed: Explicit user review
  Reviewed --> Published: Promote immutable revision
  Published --> Reopened: Load in conventional editor
  Reopened --> RevisedDraft: Edit
  RevisedDraft --> Validated: Revalidate and republish
  Rejected --> [*]
```

## Key invariants

- All data access and chart filters are governed on the server, not trusted to client widgets.
- Published manifests are immutable and versioned; editing creates a new revision.
- Workspace and subject identities originate from trusted authentication, not user-controlled request fields.
- Batik and Tenun are presentation adapters; neither defines semantic policy.
- Toba is an independent data platform, not a Farabi submodule.

## Verification roadmap

1. Restore passing Java and Flutter CI.
2. Demonstrate a governed query with denied-access and cross-tenant tests.
3. Demonstrate conventional dashboard create/edit/save.
4. Demonstrate agent-produced Batik dashboard and explicit promotion.
5. Prove reopened manifest fidelity, provenance and policy enforcement.
