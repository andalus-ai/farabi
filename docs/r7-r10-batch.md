# R7–R10 consolidated implementation branch

This branch is intentionally developed without opening a PR after each milestone.

## Implemented first
- Manifest-to-renderer reconstruction from R6.
- DashboardRuntime: workspace-scoped lookup, dataset authorization before data fetch, bounded result rows and shared dataset fetch.
- AgentDashboardWorkflow: validates an agent-proposed DashboardSpec and promotes it to an immutable versioned manifest.
- Tests for authorization-before-fetch and workspace isolation.

## Security invariants
- Do not trust prompt text as executable SQL or widget code.
- DatasetAccess is a required trusted host adapter; no permissive default.
- Chart filters are display metadata; the governed query compiler must enforce source/row/column permissions.
- Never return raw credentials or arbitrary SQL in render envelopes.

## Planned within this same branch
- Dart Batik bridge package and Tenun adapters after verifying public Dart APIs.
- HTTP contract and authenticated transport adapter.
- Loading/error/empty states and frontend integration tests.
- End-to-end manifest publication and reconstruction examples.

No PR has been created for this branch.
