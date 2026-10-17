# Andalus BI

Andalus BI is a hybrid business-intelligence platform combining governed semantic analytics, conventional dashboards, AI-assisted analytical workflows, and reusable dashboard artifacts.

## Project status

The repository is being bootstrapped. The first implementation milestone focuses on a small, testable foundation before adding data connectors, query execution, dashboards, and agent workflows.

## Architecture principles

- Keep semantic definitions independent of chart rendering.
- Treat AI-generated dashboards as versioned, reviewable artifacts.
- Keep data-source adapters replaceable.
- Make tenant authorization and provenance explicit at service boundaries.
- Prefer deterministic, testable core logic before infrastructure-specific integrations.

## Planned milestones

1. **P0 — Foundation:** domain contracts, semantic metrics, dashboard artifact model, validation, and tests.
2. Query and data-source adapters, including Toba integration.
3. Dashboard composition and chart adapter integration with Tenun.
4. Batik/A2UI integration for prompt-generated UI.
5. Governed agent workflows and dynamic-to-static artifact promotion.
6. Streaming analytics, trust, and event-driven automation.

See `docs/architecture/overview.md` for the initial architecture and `docs/roadmap.md` for implementation sequencing.
