# Runtime Security Test Plan

**Status:** Proposed test plan; no application behavior changed  
**Baseline:** Current `main` branch

This plan closes the current test gap in `runtime-core` and `runtime-quarkus` before Farabi adds external identity, tenancy, or agent-runtime integrations.

## Observed runtime seams

- `EmbeddedFarabi` composes `DurableQueryService` from injected ports and a `Clock`; `runtime-core` currently has no test classes.
- `DashboardDeliveryResource` resolves a trusted `AuthenticatedContext` and `DashboardRuntime` through CDI `Instance`, rejects missing/ambiguous adapters with 503, maps missing identity to 401, malformed UUIDs to 400, authorization failures to 403, and missing manifests to 404.
- `DashboardRuntime.load` validates workspace, subject and row limit; loads a manifest scoped to workspace; authorizes every distinct dataset before fetching any dataset; then fetches data.
- `FarabiBeans.authorizer()` intentionally denies by default until a governance adapter replaces it. The default compiler is also deliberately unavailable.
- `DashboardRuntimeProducer` requires exactly one governed `DatasetAccess` adapter; there is no permissive fallback.

These are test targets, not assertions that all host identity or deployment configurations are already secure.

## Priority 0 — authorization and disclosure invariants

| Area | Test case | Required assertion |
| --- | --- | --- |
| HTTP identity | No `AuthenticatedContext` bean | Delivery endpoint returns 503; no dashboard data is returned |
| HTTP identity | Ambiguous identity beans | Delivery endpoint returns 503 |
| HTTP identity | Identity provider throws or returns null principal | Delivery endpoint returns 401 |
| HTTP identity | Principal has blank workspace or subject | Principal construction fails closed |
| HTTP input | Malformed manifest UUID | Delivery endpoint returns 400 before runtime access |
| HTTP authorization | Runtime throws `SecurityException` | Delivery endpoint returns 403 |
| HTTP lookup | Manifest absent in caller workspace | Delivery endpoint returns 404 |
| Workspace isolation | Manifest exists only in workspace A; caller uses B | No manifest data is returned |
| Dataset authorization | One of multiple datasets is denied | No dataset fetch occurs, including for datasets authorized earlier |
| Dataset authorization | Duplicate charts reference the same dataset | Dataset is authorized/fetched once per distinct dataset |
| Limits | Limit is 0 or greater than 1000 | Runtime rejects before dataset access |
| Defaults | No host governance adapter replaces default authorizer | Query authorization always fails closed |
| Defaults | No semantic compiler is configured | Query compilation fails; executor is not called |

## Priority 1 — query pipeline and runtime composition

- Verify `EmbeddedFarabi` delegates to the supplied authorizer, compiler, executor, repository, and clock.
- Verify authorization failure prevents compilation and execution.
- Verify compiler failure prevents execution.
- Verify repository persistence precedes remote submission where the durable-query contract requires it.
- Verify duplicate submissions and retry paths preserve idempotency and do not silently duplicate remote work.
- Verify all workspace/subject fields are carried from trusted request context into the engine request; never accept them from prompt/model-generated arguments.

## Priority 2 — Quarkus integration tests

Use Quarkus test support and injected test doubles for the identity context, `DashboardRuntime`, `DatasetAccess`, and persistence ports. Cover CDI unsatisfied/ambiguous resolution and the HTTP status mapping above. Keep the default production authorizer deny-by-default; tests must not introduce a permissive production fallback.

Where the current CDI producer shape makes unsatisfied-bean testing awkward, test the resource's explicit resolution branches with controlled `Instance` test doubles and separately test application startup with valid and invalid bean cardinality.

## Test implementation order

1. Add pure `DashboardRuntime` tests in `engine` for authorization-before-fetch, workspace scoping, limits, and deduplicated dataset access. This is the most direct guard against cross-workspace data disclosure.
2. Add `runtime-quarkus` resource tests for 401/400/403/404/503 mapping and prove runtime is not called on invalid identity or UUID.
3. Add a Quarkus CDI integration test proving missing or ambiguous governed adapters do not yield a readable endpoint.
4. Add `runtime-core` composition tests for `EmbeddedFarabi` and the durable query ordering guarantees.
5. Add adversarial tests before enabling Andalus/Numou adapters: spoofed workspace/subject in prompt text, altered dataset IDs, unauthorized fields/filters, and replayed proposal digests.

## Acceptance gate

Do not connect a live agent runtime or external identity provider until Priority 0 passes in CI, and do not treat mock-only tests as proof of production identity integration. Keep a separate live contract/integration test for the selected identity provider and Andalus Runtime adapter. Record the exact tested adapter versions and trust mapping.

## Scope and non-goals

This plan does not implement authentication, authorization, row-level security, UI filter execution, approval workflows, or worker fencing. In particular, dashboard UI filters must not be treated as server-side authorization predicates; governed predicates must be enforced by the data-access adapter before data is returned.
