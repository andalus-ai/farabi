# Key workflows

## Governed query submission
```mermaid
sequenceDiagram
 participant U as User/Agent
 participant A as Auth/Policy
 participant S as Semantic Compiler
 participant E as Farabi Engine
 participant P as PostgreSQL
 participant D as Dremio/Toba
 U->>E: Query intent + trusted identity
 E->>A: authorize
 A-->>E: allow/deny
 E->>S: compile governed semantics
 S-->>E: source-bound plan
 E->>P: persist PENDING
 E->>D: submit SQL
 D-->>E: provider job id
 E->>P: CAS PENDING to SUBMITTED
 E-->>U: execution reference
```

## AI to durable dashboard
```mermaid
flowchart LR
 P[Prompt] --> I[Intent and policy]
 I --> Q[Governed query plan]
 Q --> R[Results]
 R --> V[Tenun chart spec]
 V --> B[Batik A2UI draft]
 B --> H[Human review and edit]
 H --> A[Versioned artifact]
 A --> S[Static dashboard with refresh]
```

Promotion must capture semantic model revision, data source references, query plan hash, chart specs, layout, policy snapshot reference, creation actor, prompt provenance and refresh strategy. A static artifact is a durable **definition**, not a promise that underlying data never changes.

## Conventional authoring
Choose dataset -> define dimensions/measures -> preview authorized query -> compose Tenun charts -> save dashboard draft -> review/publish -> schedule refresh.

## Worker lifecycle (target)
Claim with fenced lease -> renew lease -> poll provider -> write conditional state transition -> publish result metadata -> release. Stale worker writes must be rejected.
