# Intelligent static and dynamic dashboards

One governed path serves both authoring modes (see `PRODUCT_VISION_AND_ENGINEERING_GUARDRAILS.md`).

```
prompt ──► DashboardPlanner.Proposer ──► DashboardSpecValidator ──► DynamicDraft (immutable, expiring)
          (sees only authorized catalog)   (catalog + grants + kind rules)        │
                                                                                  ▼
 conventional editor ── revise()/editPublished() ───────────────────────► LiveDashboardRuntime.preview
                                                                                  │ reviewer sees digest
                                                                                  ▼
                       promote(draft, digest) ── re-validate ──► immutable Manifest (revision n+1)
                                                                                  │
                                                  LiveDashboardRuntime.loadPublished (static, re-validated each load)
```

## Components (engine, no framework dependencies)

| Class | Role |
| --- | --- |
| `SemanticModel` | Datasets, measures (with aggregation), typed dimensions; all identifiers validated once. `viewFor()` returns only what the caller may see. |
| `StructuredQueryCompiler` | `Query` (1 measure, ≤2 dimensions, typed filters, bounded limit) → quoted SQL. A `ChartSpec` fully determines its query. |
| `DashboardSpecValidator` | Single trust boundary for agent *and* human specs. Reports every issue at once; "forbidden" and "missing" datasets are indistinguishable. |
| `DashboardPlanner` / `HeuristicProposer` | Proposer port + deterministic offline baseline (intent words → chart kinds, states its assumptions). An LLM adapter implements the same port. |
| `LiveDashboardRuntime` | validate → compile all charts → only then fetch → insights. Same path for preview and published dashboards. |
| `InsightEngine` | Explainable insights (top contributor, concentration, latest change, trend, robust outliers) with numeric evidence. |
| `DynamicDashboardService` | propose / preview / promote / editPublished / revise. Promotion needs the reviewed `SpecDigest`. |

## Guarantees covered by tests

- Agent output is data: unauthorized or malformed proposals are rejected and nothing is stored or executed.
- Agents only see the caller's authorized catalog.
- No data is fetched unless every chart validates; revoking a grant stops already-published dashboards immediately.
- Promotion publishes exactly the reviewed spec (digest check), refuses expired drafts, and never mutates a published revision.
- Cross-workspace and cross-subject access to drafts and manifests is denied.
- Filter values are re-parsed by declared type; injection attempts are rejected.

## Not done yet (do not treat as complete)

- **Foundation, not production.** No real `ChartDataSource`: the Dremio adapter submits jobs, but result retrieval into rows does not exist yet.
- No JDBC `DraftRepository`, no HTTP endpoints, no LLM proposer adapter (it must parse model JSON into `DashboardPlanner.Proposal`; the validator already guards it).
- `DashboardTransport.Document` (v1) does not carry insights; add an additive field or a v2 before the Flutter loader can show them.
- Revision numbers derive from the parent manifest; add a uniqueness constraint (lineage, revision) to make concurrent promotions fail safely.
- No time grains (day/month/year) and no conversational refinement yet.

## Verification

Compiled with `javac` 21 and run with the JUnit console launcher against the `engine` module: 53 tests (18 existing, 35 new), 0 failures. Maven, the adapters, Quarkus and Flutter were not built in this environment.
