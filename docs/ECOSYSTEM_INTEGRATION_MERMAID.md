# Andalus Analytics Ecosystem — Integration and Deployment Views

These are **target architecture** diagrams. Each repository is independently versioned and deployable where applicable.

## Dependency direction

```mermaid
flowchart LR
  subgraph Product["Farabi Product"]
    FEngine["Farabi Engine"]
    FRuntime["Farabi Quarkus Runtime"]
    FFlutter["Farabi Flutter Integration"]
  end
  subgraph Independent["Independent Ecosystem Products"]
    Toba["Toba Lakehouse"]
    Numou["Numou Capabilities"]
    Batik["Batik A2UI"]
    Tenun["Tenun Charts"]
    Andalus["Andalus Agents"]
  end
  FRuntime --> FEngine
  FRuntime -->|"governed query adapter"| Toba
  FRuntime -->|"identity / tenant adapter"| Numou
  FRuntime -->|"agent orchestration port"| Andalus
  FFlutter -->|"UI extension"| Batik
  FFlutter -->|"renderer adapter"| Tenun
  FFlutter -->|"dashboard delivery API"| FRuntime
```

**No reverse dependencies:** Toba, Numou, Batik and Tenun must remain independent of Farabi.

## Example deployment topology

```mermaid
flowchart TB
  Browser["Analyst / Browser / Flutter Client"] --> Gateway["Authenticated API Gateway"]
  Gateway --> Runtime["Farabi Quarkus Runtime"]
  Runtime --> Identity["Numou-backed Identity / Tenant Adapter"]
  Runtime --> Engine["Farabi Domain Engine"]
  Engine --> Policy["Governance Ports"]
  Engine --> ManifestDB[("Manifest / Metadata Store")]
  Engine --> QueryAdapter["Governed Query Adapter"]
  QueryAdapter --> Toba["Toba Control Plane"]
  Toba --> Dremio["Dremio Execution Engine"]
  Toba --> Other["Other Engine"]
  Runtime --> Workers["Durable Job Workers"]
  Workers --> Engine
```

Deployment nodes are illustrative, not required one-to-one services. Domain authorization must remain authoritative in the backend regardless of gateway or client controls.

## Dashboard artifact revision model

```mermaid
erDiagram
  WORKSPACE ||--o{ DASHBOARD : owns
  DASHBOARD ||--|{ DASHBOARD_REVISION : has
  DASHBOARD_REVISION ||--|{ CHART_SPEC : contains
  DASHBOARD_REVISION ||--o{ PROVENANCE : records
  CHART_SPEC }o--|| DATASET_BINDING : references
  DASHBOARD_REVISION {
    string revision_id
    int schema_version
    string published_by
    datetime published_at
  }
  CHART_SPEC {
    string chart_id
    string renderer_kind
    string layout
    string filters
  }
```

Conceptual entities; actual database schema and naming are defined by implemented migrations.

## Contract ownership matrix

| Contract | Owner | Consumer | Required property |
| --- | --- | --- | --- |
| Semantic query / policy | Farabi engine | Runtime, agents | Server-side authorization |
| Manifest schema / revisions | Farabi engine | Builder, runtime | Immutable published snapshots |
| A2UI node schema | Batik | Farabi Flutter adapter | Bounded validated input |
| Chart configuration | Tenun | Farabi Tenun adapter | Versioned renderer mapping |
| Query/catalog APIs | Toba | Farabi data adapter | Tenant-scoped execution |
| Identity / tenant capabilities | Numou | Farabi, Toba adapters | Trusted context and isolation |

## Verification boundaries

A passing unit test for a renderer adapter does not establish full compatibility with Batik, Tenun, Toba or Numou. Integration verification requires pinned dependency versions, executable tests, denied-access scenarios, and CI evidence.
