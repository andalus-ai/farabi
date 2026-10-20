# R1 integration checkpoint

This PR groups reference governance, constrained semantic compilation, immutable dashboard promotion and PostgreSQL artifact persistence into one review milestone.

## Implemented
- Dataset-scoped explicit grants; deny by default for unknown grants.
- Trusted catalog identifier validation and narrow measure compilation.
- Immutable chart specs, draft/published revision contracts and promotion use case.
- JDBC persistence for published artifacts, unique (draft, revision) constraint and chart encoding.
- Unit tests for policy denial, compiler injection rejection, immutable snapshots, workspace isolation and chart serialization.

## Not yet production-ready
- The reference authorizer accepts a subject string; authentication and trusted principal binding must be implemented in the runtime.
- Semantic compilation supports only SUM over one validated column. Row filters, masks, dimensions, joins and parameterized predicates are absent.
- No Tenun renderer, Batik A2UI frontend or AI agent execution is included.
- Artifact encoding is a version-0 internal representation, not a public interchange format. Migrate to a versioned JSON schema before exposing externally.
- The current JDBC `find` port is not tenant-scoped at the SQL layer; use application-layer scoped access only from trusted callers and replace with scoped SQL before public API exposure.
- No automated database migrations or integration tests with live PostgreSQL.
- Quarkus remains fail-closed; no public query endpoint.

## Next consolidated milestone
Identity-backed runtime and scoped SQL, semantic revisioning, worker leases/fencing, reconciliation, result fetching, adapter integration tests and Quarkus startup tests. Keep these changes in a cohesive PR rather than opening a PR per subtask.
