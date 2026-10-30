# Farabi Product Vision and Engineering Guardrails

> **Normative guidance.** Applies to product decisions, architecture, implementations, tests, and AI coding agents. When existing code contradicts these principles, document the conflict and plan a safe migration rather than silently perpetuating it.

## Vision

Farabi is an **enterprise-grade, AI-native, governed Business Intelligence and analytics platform** that unifies conventional self-service BI and agentic, natural-language analytics in one coherent product.

**North star:** A user can ask a question, obtain a governed interactive dashboard, explore it, promote it to a durable dashboard artifact, reopen it in the conventional editor, and publish/share it without losing lineage, permissions, or reproducibility.

## Mission

Make trustworthy analytics accessible through both visual authoring and natural-language interaction while preserving enterprise governance, composability, portability, and human control.

## Non-negotiable objectives

1. **One product, two complementary modes:** conventional dashboard authoring and agentic BI use the **same** semantic layer, authorization, query contracts, visualization schema, artifact lifecycle, and runtime.
2. **Dynamic → static is first-class:** agent-produced A2UI is ephemeral until explicitly reviewed/promoted into a validated, versioned, immutable dashboard manifest. The manifest can be reopened, edited via a new revision, shared, and refreshed.
3. **Governance before execution:** tenant/workspace isolation, identity, dataset and column authorization, policy enforcement, limits, and auditing are enforced server-side. An agent cannot bypass these controls.
4. **Library-first hexagonal core:** `engine` contains domain rules and ports, not Quarkus, Flutter, JDBC, vendor SDKs, or transport-specific types. Runtime and adapters remain replaceable.
5. **Independent ecosystem components:** Toba is an independent lakehouse and preferred native integration, not a Farabi submodule; other sources and execution engines remain supported. Tenun provides charting; Batik provides A2UI; Andalus provides agent orchestration; Numu provides reusable identity/tenant capabilities where appropriate.
6. **Human control and explainability:** show query intent, data provenance, assumptions, permissions, and promotion changes. Require explicit user authorization for publishing, sharing, or consequential external actions.
7. **Operational quality:** durable execution, idempotency, safe retries, cancellation, observability, audit, migration compatibility, performance limits, and meaningful tests are part of each production feature.

## Reference user journey

```text
Conventional BI Studio ─────────────────────────┐
  dataset → chart → layout → save                │
                                                ▼
Natural-language request → Agent orchestration → Governed semantic plan
                                                │
                                     authorize → execute → results
                                                │
                                      dynamic Batik A2UI
                                                │
                                       review / validate
                                                │
                                      promote to manifest
                                                │
                             reopen / edit / publish / share
                                                │
                                conventional dashboard runtime
```

Both authoring paths converge on **one canonical dashboard specification** and versioned artifact lifecycle. A renderer envelope is a delivery format, not the canonical domain model.

## Architecture boundaries

| Area | Owns | Must not own |
| --- | --- | --- |
| `engine` | semantic contracts, governance ports, query lifecycle, dashboard specs, promotion use cases | Quarkus/CDI, Flutter widgets, SQL-driver implementation |
| `runtime-core` | framework-neutral composition and orchestration | UI implementation |
| `runtime-quarkus` | HTTP APIs, dependency wiring, deployment/security integration | domain invariants duplicated from engine |
| Data adapters | governed access to Toba/Dremio/JDBC and other sources | cross-tenant authorization shortcuts |
| Batik integration | dynamic agent UI and interaction contracts | source-of-truth manifests or security decisions |
| Tenun integration | rendering chart specifications | semantic SQL, data authorization, business logic |
| Numu integration | reusable identity and tenancy where compatible | coupling the core to one identity provider |

## Prioritized delivery milestones

**P0 — Restore build integrity and verify baseline.** Fix existing CI/compiler/test failures. Audit current modules, distinguish implemented from planned capabilities, and record known gaps.

**P1 — Governed analytics vertical slice.** Dataset catalog, semantic model, query planning, policy checks, execution, typed results, limits, provenance, and tests using at least one real adapter.

**P2 — Conventional BI vertical slice.** Dataset selection, chart authoring, filters, layouts, save/edit/revision/publish, and persisted retrieval.

**P3 — Agentic BI vertical slice.** Natural-language request → validated semantic plan → authorized execution → Batik dynamic dashboard, with transparent user-facing assumptions and error handling.

**P4 — Signature dynamic-to-static slice.** Review → promotion → immutable manifest → reopen in conventional builder → edit as a new revision → publish/share. Prove round-trip fidelity and policy enforcement.

**P5 — Enterprise hardening.** Identity/tenant integration, RBAC/ABAC, audit, scheduling, caching, concurrency, observability, deployment, and operational recovery.

**P6 — Advanced agentic analytics.** Explainable insight generation, reusable workflows, proactive analysis, and assisted dashboard evolution, all behind governance.

Milestones may overlap when needed, but **do not prioritize isolated renderer polish over the missing core user journey**.

## Acceptance criteria for the north-star flow

A representative integration scenario must demonstrate:

- An authenticated user requests a revenue analysis in natural language.
- An agent produces a **structured, validated** plan, not unrestricted SQL.
- The backend authorizes workspace, datasets, columns, and query constraints **before** execution.
- A dynamic Batik view renders authorized results using Tenun/native chart adapters.
- The user reviews and explicitly promotes the result into a versioned dashboard manifest.
- The same manifest reopens in conventional BI, preserving bindings, layout, filters, provenance, and revision identity.
- Editing creates a new revision without mutating the published snapshot.
- Another workspace cannot read, execute, or publish the artifact.
- The pipeline has automated tests and passing CI evidence.

## Engineering delivery policy

- Prefer **coherent vertical batches** spanning domain, runtime, adapters, UI, tests, and docs over one-off small PRs.
- Respect the user's preference to **avoid frequent PRs**. Work on a consolidated development branch; create a PR only when explicitly requested or agreed.
- Inspect the current repository and CI before editing. Branches may be merged/deleted between sessions; never assume a branch still exists.
- Do not claim compilation, test success, deployment, or runtime compatibility without observed evidence. Record exact checks and unresolved failures.
- Never fabricate third-party APIs or declare integration complete because a factory or placeholder exists. Pin and verify real Tenun/Batik/Toba contracts.
- Every meaningful change should update tests and documentation; security-critical flows need negative tests (cross-workspace, denied dataset, invalid manifest, replay).
- Do not expose ungoverned SQL or trust workspace/subject IDs supplied directly by clients.
- Keep generated AI UI as **untrusted input**: validate schema, bound resources, policy, and action permissions.
- Maintain backwards-compatible manifests/APIs or provide explicit migrations.
- Report delivery honestly: **committed / compiled / tested / integration-verified / production-ready** are different states.

## Decision test for every proposed feature

Before implementation, answer:

1. Which Farabi objective and user journey does this advance?
2. Does it reinforce the shared engine and dynamic-to-static lifecycle?
3. Are governance and tenant isolation enforced server-side?
4. Are component boundaries and provider replaceability preserved?
5. Can we prove the feature works end-to-end rather than only through mocks?
6. Is this a coherent batch, with tests, docs, and clear acceptance criteria?

If these questions cannot be answered, **re-scope or defer** the work.

## Definition of done

A feature is done only when the agreed user journey is functional; required security boundaries are enforced; tests and CI pass; public contracts and migration implications are documented; and no placeholder integration is misrepresented as complete. Production readiness additionally requires operational, security, and performance validation.
