# Farabi implementation roadmap

## Release strategy
Prefer **one cohesive PR per verified milestone**, not dozens of tiny PRs. Each milestone must include code, tests, docs, migration notes and rollback strategy. Keep PRs open until CI is green and reviewable. Never bypass branch protection to save time.

| Phase | Deliverable | Exit gate |
|---|---|---|
| Foundation (this PR) | Java engine, adapters, Quarkus skeleton, architecture and CI | Maven reactor verifies; no raw query route |
| R1: Governed runtime | Real identity/policy and semantic compiler, authenticated API | Deny-by-default tests, workspace isolation |
| R2: Reliable execution | Provider polling, cancellation, fenced workers, reconciliation, audit | Multi-worker race and failure injection tests |
| R3: Conventional BI | Semantic catalog, Tenun chart spec, dashboard CRUD, filters | User can author/publish dashboard |
| R4: Agentic BI | Agent planning, Batik A2UI, governed tool contracts | Agent cannot bypass policy; deterministic artifact schema |
| R5: Promotion | Dynamic draft -> immutable versioned dashboard artifact | Reproducible rendering and revision history |
| R6: Enterprise/scale | Sharing, RBAC/ABAC, observability, caching, multi-source | Load, security and recovery benchmarks |

## Critical path
Identity -> governed semantic compiler -> durable query lifecycle -> results -> chart contract -> dashboard artifact -> agent orchestration -> promotion.

## Testing pyramid
Engine unit tests; adapter contract tests; PostgreSQL integration tests; Dremio transport fixtures; Quarkus startup smoke tests; policy and tenant isolation tests; worker race tests; UI and A2UI schema conformance.

## Migration from earlier BI work
Old PRs #25–#30 in the prior repository are **historical reference**, not automatically present or merged here. This foundation reestablishes compatible architectural ideas in the new repository. Compare legacy package/API and SQL schema contracts before copying existing BI worker or semantic implementation.

## PR hygiene and account limits
Use a long-lived integration milestone branch with small local commits, one PR per cohesive deliverable, and batched Git tree commits when appropriate. GitHub account suspension is not established to be caused by PR count; do not treat reducing PR frequency as a guarantee against account restrictions.
