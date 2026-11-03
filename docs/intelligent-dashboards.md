# Intelligent static and dynamic dashboards

One governed path serves both authoring modes (see `PRODUCT_VISION_AND_ENGINEERING_GUARDRAILS.md`).

```
prompt ──► DashboardPlanner.Proposer ──► DashboardSpecValidator ──► DynamicDraft (immutable, expiring)
          (sees only authorized catalog)   (catalog + grants + kind rules)        │
                                                                                  ▼
 conventional editor ── revise()/editPublished() ───────────────────────► LiveDashboardRuntime.preview
                                                                                  │ reviewer sees digest
                                                                                  ▼
          promote(draft, digest) ── single-use claim ── re-validate ──► immutable Manifest (revision n+1)
                                                                                  │
                                                  LiveDashboardRuntime.loadPublished (static, re-validated each load)
```

Chart data flows `Query → StructuredQueryCompiler → Plan → ChartDataSource`. `DurableChartDataSource` is the production
source: it submits through `DurableQueryService` (persist-before-execute, `PolicyAuthorizer` re-check), waits within a hard
deadline, pages results through `QueryLifecycleService`, and cancels the remote job on timeout.

## Components (engine, no framework dependencies)

| Class | Role |
| --- | --- |
| `SemanticModel` | Datasets, measures (with aggregation), typed dimensions, optional time grains (DAY…YEAR); all identifiers validated once. `viewFor()` returns only what the caller may see. |
| `StructuredQueryCompiler` | `Query` (1 measure, ≤2 dimensions, typed filters, bounded limit) → quoted SQL; grains compile to `DATE_TRUNC`. A `ChartSpec` fully determines its query. |
| `DashboardSpecValidator` | Single trust boundary for agent *and* human specs. Reports every issue at once; "forbidden" and "missing" datasets are indistinguishable. |
| `DashboardPlanner` / `HeuristicProposer` | Proposer port + deterministic offline baseline (intent words → chart kinds, first-mentioned measure is primary, states its assumptions). An LLM adapter implements the same port. |
| `LiveDashboardRuntime` | validate → compile all charts → only then fetch → check result shape → insights. Same path for preview and published dashboards. |
| `DurableChartDataSource` | Durable, grant-checked, deadline-bound chart execution over the existing query lifecycle. Row cap is enforced, never truncated silently. |
| `InsightEngine` | Explainable insights (top contributor, concentration, latest change, trend, robust outliers) with numeric evidence. Delivered in `DashboardTransport.Document.insights` (additive field, version stays 1). |
| `DynamicDashboardService` | propose / preview / promote / editPublished / revise. Promotion needs the reviewed `SpecDigest`, is single-use, and revisions share a lineage id. |
| `JdbcDynamicDraftRepository` (+ `V5` migration) | Workspace-scoped draft storage; promotion claim is one conditional `UPDATE`. Standard SQL types so it runs on PostgreSQL and H2. |

## Guarantees covered by tests

- Agent output is data: unauthorized or malformed proposals are rejected and nothing is stored or executed.
- Agents only see the caller's authorized catalog.
- No data is fetched unless every chart validates; revoking a grant stops already-published dashboards before any query runs.
- Promotion publishes exactly the reviewed spec (digest check), refuses expired drafts, and never mutates a published revision.
- **Replay:** a draft can be promoted once; a failed publish releases the claim so the reviewer can retry.
- **Concurrent edits:** revisions of one dashboard share a lineage id stored as the manifest `draft_id`, so the existing
  `UNIQUE(workspace_id, draft_id, revision)` makes a stale second edit of the same revision fail instead of forking.
- Cross-workspace and cross-subject access to drafts and manifests is denied (in memory and in SQL).
- Filter values are re-parsed by declared type; injection attempts are rejected. Filters compare raw columns, never grain expressions.
- Chart queries are denied before submission without a grant, time out with remote cancellation, and fail loudly on a
  result shape mismatch (e.g. a provider that case-folds aliases) instead of rendering blanks.

## Not done yet (do not treat as complete)

- **No HTTP surface.** `runtime-quarkus` still serves the legacy `DashboardRuntime`. Endpoints for propose/preview/promote and for
  `LiveDashboardRuntime.loadPublished` need a trusted identity bridge and were not written because Quarkus could not be built here.
- `DurableChartDataSource` needs something to advance execution records (the `QueryPollingWorker` scheduler); only its wait hook is provided.
- No LLM proposer adapter (it must parse model JSON into `DashboardPlanner.Proposal`; the validator already guards it) and no conversational refinement.
- The Flutter model (`FarabiDashboard.fromJson`) does not read `insights` yet; it was not changed or run.
- Time grains are catalog-defined (one dimension per grain); a per-chart grain selector would change the manifest schema.
- No result caching, draft expiry sweeper, or audit sink implementation beyond the `Audit.Sink` port.

## Verification

Compiled with `javac` 21 and run with the JUnit console launcher: `engine` 66 tests, `adapter-jdbc` 6 (the new repository runs the real
V5 migration on H2 2.2.220), `adapter-dremio` 4, all passing. Maven, Quarkus and Flutter were not built; `adapter-jdbc/pom.xml` adds `h2:2.3.232`
as a test dependency, which was not resolved here.
