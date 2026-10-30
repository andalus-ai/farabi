# AGENTS.md — Farabi Repository Instructions

**Scope:** All directories in this repository unless overridden by a more specific `AGENTS.md`. This file is binding guidance for coding agents and contributors.

## Read first

1. [Product vision and engineering guardrails](docs/PRODUCT_VISION_AND_ENGINEERING_GUARDRAILS.md) — authoritative objectives, boundaries, milestones, acceptance criteria, and definition of done.
2. `README.md`, `docs/architecture.md`, `docs/vision.md`, `docs/roadmap.md`, and relevant ADRs.
3. Existing module code, tests, dependency manifests, and recent CI failures.

If documents conflict, **do not silently reinterpret product direction**. Preserve the north-star flow and surface the conflict for resolution.

## North-star invariant

**Prompt → governed semantic query → authorized results → dynamic Batik A2UI dashboard → explicit review/promotion → immutable versioned manifest → conventional dashboard editor/runtime → publish/share.**

Conventional and agentic BI are **two authoring experiences for the same analytics platform**, not isolated products.

## Must do

- Build **real vertical product capabilities**, not endless isolated chart adapters.
- Maintain a Java 21, library-first, hexagonal `engine` with replaceable ports.
- Keep Quarkus in runtime wiring; Toba independent; Batik and Tenun at presentation boundaries; reuse Numu modules where compatible.
- Enforce identity, tenant/workspace isolation, dataset/column policies, limits, and audit **on the server**.
- Treat agent plans and A2UI payloads as untrusted: validate, authorize, constrain, and trace them before use.
- Preserve dashboard manifest schema, provenance, revision, filter, binding, and layout fidelity across dynamic-to-static promotion.
- Add positive and negative tests; inspect and fix broken CI before adding features that depend on it.
- Verify external package APIs against real installed versions. A registry bridge is not a completed integration.
- Favor **comprehensive batches and fewer PRs**. Do not open a PR automatically; wait for explicit user approval.
- Check the actual branch/ref before writes; never assume previous development branches still exist.
- Provide precise evidence in progress reports: commit SHA, tests run, CI conclusion, known gaps.

## Must not do

- Do not transform Farabi into a Flutter-only dashboard framework, a Quarkus-only service, or a wrapper around one data engine.
- Do not merge Toba into Farabi or bind core domain contracts to Dremio, Tenun, Batik, or vendor SDKs.
- Do not bypass governance for AI-generated queries, chart filters, sharing, or publication.
- Do not execute unrestricted agent-generated SQL or use client-supplied workspace identity as trusted authentication.
- Do not claim end-to-end success from mocks, generated schemas, adapters, or unexecuted tests.
- Do not introduce silent data aggregation, lossy manifest conversions, or mutable published snapshots.
- Do not open many small PRs or spend successive batches polishing rendering while the north-star user journey remains incomplete.

## Workflow for each implementation batch

1. **Inspect:** check current main/branch, repository structure, existing implementation, test failures, and CI status.
2. **Align:** name the product objective, user journey, and acceptance criteria.
3. **Design:** identify engine contracts, runtime ports, adapters, UI, persistence, governance, and migration impacts.
4. **Implement:** deliver a coherent vertical slice; use typed contracts and explicit error handling.
5. **Verify:** run targeted tests and relevant full-module CI; include denied-access and tenant-isolation cases.
6. **Document:** update architecture, contracts, usage, and remaining limitations.
7. **Report:** distinguish committed, compiled, tested, integrated, and production-ready states; identify next highest-value gap.

## Priority order

P0 broken build/CI → P1 governed analytics → P2 conventional BI builder → P3 agentic BI → P4 dynamic-to-static round-trip → P5 enterprise hardening → P6 advanced agents.

Security and compatibility constraints apply at **every** priority, not only P5.

## Completion gate

Do not call a feature complete unless the user-visible scenario works with real adapters, tests pass, server-side authorization is demonstrated, and documented contracts match the implementation. Otherwise label it **foundation**, **partial**, or **unverified**.

## Escalation

When a request conflicts with these guardrails, explain the tradeoff and propose a compliant implementation. User-approved changes to product direction should update the normative vision document and this file together.
