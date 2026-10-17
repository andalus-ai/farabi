# Andalus BI architecture (initial)

## Product shape

Andalus BI has two authoring paths that converge on the same dashboard artifact model:

1. **Static authoring** — a user composes and edits dashboards directly.
2. **Agent-assisted authoring** — a user asks a question; a governed semantic query is planned and executed; the result is rendered as a dynamic view; the user may promote it into a persistent, editable dashboard artifact.

A dynamic agent response is not itself a saved dashboard. Promotion must produce a validated artifact with a stable ID, owner/workspace scope, version, provenance, and a reproducible specification.

## Logical layers

- **API/application:** request validation, authorization, orchestration, and lifecycle operations.
- **Semantic layer:** metric and dimension identities, descriptions, aggregation rules, and query constraints.
- **Query runtime:** compiles semantic requests into source-specific plans; connectors remain replaceable.
- **Artifact layer:** dashboard definitions, widgets, layouts, versions, and provenance.
- **Presentation adapters:** Tenun chart adapter and Batik/A2UI UI adapter behind interfaces; do not couple core domain objects to their concrete APIs.
- **Agent/workflow integration:** proposes queries and UI specifications, but cannot bypass semantic validation, authorization, or artifact validation.
- **Trust and operations:** data freshness, quality signals, audit trail, and execution status.

## Initial implementation boundary

P0 intentionally provides dependency-light domain contracts and deterministic validation. It does not claim to implement SQL execution, authentication, a persistent database, Tenun/Batik integration, or an agent runtime. Those integrations should be added behind ports after their actual APIs and version compatibility are verified.

## Key invariants

- A metric ID is stable and distinct from its display label.
- An artifact is immutable by version: edits create a new version.
- Widgets refer to semantic IDs rather than embedding arbitrary SQL.
- An artifact cannot be promoted unless it has at least one valid widget.
- Untrusted model output is treated as input and validated before use.
