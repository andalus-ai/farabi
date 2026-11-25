# M2 — Multi-source data foundation

**Status:** Initial engine contracts, governed registry use cases, generic JDBC metadata discovery and a bounded synchronous JDBC query adapter are committed on `feature/m2-multisource-foundation`. Persistent source-definition storage and initial Quarkus JDBC provider composition are present. The latest full Maven reactor CI run passed. Numu-backed authorization, authenticated resource endpoints, pooling, durable/asynchronous execution and real PostgreSQL/MySQL integration remain outstanding.

## Objective

Allow Farabi to manage multiple workspace-scoped analytical connections through provider-neutral contracts. Keep metadata persistence (`adapter-jdbc`) separate from customer data connectivity.

## Implemented in this batch

- `DataSourceContracts`: connection definitions, secret references, provider kinds, health, capabilities, catalog entries and column metadata.
- `DataSourcePorts`: connection registry, provider, provider registry, secret resolver and connection authorization ports.
- `DataSourceRegistryService`: authorization-before-registration/discovery, provider availability checks and workspace-scoped lookup.
- Unit tests for immutable connection properties, rejection of credential-bearing endpoints, unknown providers, authorization order and workspace isolation.
- `adapter-jdbc-source`: generic JDBC provider for catalog/schema/table/view/column discovery, JDBC-to-Farabi logical type mapping, capability/dialect identification and health checks. It receives a connection factory from the host runtime; secret resolution, pooling and driver configuration are intentionally not embedded in the adapter.
- H2 metadata tests covering schema/table/column discovery, JSON and common JDBC type mapping, capability reporting, health checks and read-only connection mode.
- `SecretBackedJdbcConnectionFactory` resolves a `SecretReference` only when opening a JDBC connection, translates the optional `username` property, rejects inline `user`/`password` properties, wipes the resolver-owned `char[]`, removes the transient JDBC password property, applies read-only mode, and closes the connection if read-only setup fails. JDBC `Properties` requires a `String` password, which cannot be zeroed; the property is removed immediately after open, and the opener/driver must not retain it. A mandatory injected connection policy must validate vendor-specific TLS (including certificate/hostname verification), timeouts and permitted driver options; there is deliberately no permissive default.
- `QueryContracts.Plan` now supports ordered, typed positional parameters; JDBC uses `PreparedStatement` binding and requires an explicit JDBC type for SQL NULL.
- `QueryLifecycle.ResultPage` preserves nullable values and exposes a `truncated` flag.
- The Dremio REST adapter has a DremioSourceProvider bridge to DataSourceContracts.Provider. `DremioCatalog` implements catalog/source, folder/schema, table/view and column discovery through the v3 catalog REST API; Dremio entity IDs remain opaque in Farabi. `DremioSourceFactory` composes catalog, SQL executor and async job lifecycle per ConnectionDefinition and resolves only secret references through the engine SecretResolver port. The catalog transport is injectable for deterministic HTTP fixture tests. The Quarkus runtime now composes the factory, bridges the provider into the governed DataSourcePorts.Provider SPI, and registers both Dremio and generic JDBC under stable provider keys. Catalog navigation accepts opaque IDs or unambiguous names at the runtime adapter boundary. Dremio outbound access is deny-by-default and requires the exact-host `FARABI_DREMIO_ALLOWED_HOSTS` allowlist; the destination is validated before resolving its credential. Live-version compatibility testing against a deployed Dremio instance remains outstanding.
- Dremio SQL submission now rejects non-empty positional parameter lists before making a network request. The current Dremio SQL REST adapter sends SQL text only; silently dropping typed parameter values was unsafe. Parameterized-query capability is therefore not advertised and the parameterized executor fails explicitly.
- `JdbcQueryProvider` implements the existing `QueryPorts.Executor` and `QueryLifecycle.Provider` contracts for bounded synchronous query execution, read-only connection mode, 30-second statement timeout, fetch-size/result/page limits, process-local LRU result retention and result paging.
- `JdbcConnectionRegistry` persists provider-neutral source definitions and non-secret connection properties in the Farabi metadata database. It scopes every read/list/delete by workspace, stores secret references only, and relies on a composite `(workspace_id, source_id)` key. Migration `V22__farabi_data_sources.sql` creates the source and property tables.
- Quarkus composes the `adapter-jdbc-source` provider, JDBC-backed registry, secret resolver, and fail-closed outbound policy. It also registers the Dremio provider and its source-scoped secret-backed factory in the same provider registry. Dremio outbound hosts are separately controlled by `FARABI_DREMIO_ALLOWED_HOSTS` (comma-separated exact hostnames; empty denies all Dremio connections). PostgreSQL and MySQL endpoints must use explicitly allowlisted hosts; TLS certificate/hostname verification is forced; only username/password driver properties are accepted. Secret resolution returns a mutable `char[]` for wiping.
- The previous static Hikari source-map wiring was removed because it bypassed the workspace-scoped provider-neutral registry and depended on an obsolete adapter contract. The current connection opener uses DriverManager (no connection pool yet); add a pool keyed by immutable source identity after registry/secret rotation lifecycle is designed.
- Runtime authorization remains deny-by-default until Numu-backed identity is installed. The JDBC query lifecycle provider is composed with `WorkspaceScopedJdbcConnectionFactory`, but no public source-management or query endpoint is claimed in this batch. Database migrations must be applied before enabling source-registry use.
- `QueryContracts.Plan` carries workspace and subject context. `JdbcQueryProvider` rejects plans without both values. `ExecutionContextCompiler` decorates the semantic compiler and overwrites any context emitted by that compiler with the workspace/subject on the application request, while rejecting source mismatches. This is safe only when the host runtime constructs `Request` from authenticated server identity; the request DTO must not accept workspace or subject from clients. `WorkspaceScopedJdbcConnectionFactory` authorizes `Action.QUERY` before registry lookup, resolves by `(sourceId, workspaceId)`, validates the returned identity and workspace, rejects non-JDBC or non-read-only definitions, then delegates connection opening to the secret-backed factory. The plan context must be populated from authenticated server-side identity, never client-supplied workspace or subject fields.

## Security invariants

1. Domain connection definitions contain secret references, never passwords or tokens.
2. Endpoint URIs cannot embed user-info, query parameters or fragments.
3. Registration and discovery require an explicit authorization decision.
4. Source lookup is scoped by workspace.
5. Provider adapters are selected by a stable provider key and must not become authorization authorities.
6. Read-only is an explicit connection property; adapters must enforce it at the connection/session level, not merely trust the flag.
7. Workspace and subject identity must be derived from a verified server-side principal. A caller-supplied workspace or subject is never an authorization input.
8. A Numu permission decision must bind the authenticated principal to the canonical tenant/workspace resource path. A plain workspace ID or a client-provided path is insufficient.

## Numu integration findings and design gate

Numu's current `AuthCheckService` supports single/bulk permission checks and principal resolution, but its protobuf remains inside the deployable `numou-identity-adapter`. The permission-check RPC currently has no explicit tenant binding, and its current implementation resolves effective permissions from the requested path. Do not assume that a tenant-looking path alone proves tenant membership or principal binding.

A Numu-side contract proposal is now documented at [AUTH-CHECK-SERVICE-TENANT-SCOPE-CONTRACT.md](https://github.com/andalus-ai/numu/blob/main/docs/architecture/AUTH-CHECK-SERVICE-TENANT-SCOPE-CONTRACT.md). It proposes:

- Canonical tenant-first paths: `tenant.<tenant-id>.<capability>.<resource-type>.<resource-id>`, with segment-boundary inheritance and no caller-supplied paths.
- Explicit tenant binding in single and bulk permission checks, verified against principal tenant binding or active tenant membership.
- Workload authentication and delegated-check policy so a consumer cannot ask Numu to authorize arbitrary principal IDs.
- Consumer-owned permission vocabulary. Farabi's proposed permissions are `farabi.source.register`, `farabi.source.view`, `farabi.source.test`, `farabi.source.discover`, `farabi.source.query`, and `farabi.source.delete`.
- A reusable, versioned protobuf contract artifact separate from the deployable Identity adapter.

This is a **contract proposal**, not an implemented Numu API release. Farabi must not integrate against it as though the server already enforces these guarantees. The next cross-repository implementation gate is to extract/version the protobuf contract, implement tenant and workload enforcement in Numu, and add server tests for tenant mismatch, membership, inheritance and spoofed delegated principal requests.

Farabi's runtime must then implement the Numu client behind the existing engine `ConnectionAuthorizer` port. It must derive principal, tenant and workspace from verified server-side identity, construct canonical paths from validated identifiers, and fail closed on timeout, unavailable service, malformed response or identity mismatch. Authorization must happen before source lookup, metadata discovery, secret resolution, connection opening or query execution.

Until the Numu contract is released and enforced, the existing Quarkus authorizer intentionally denies all source operations. Do not substitute local role names or unverified JWT claims for Numu's authoritative decision.

## Next implementation batches

### M2-A — Generic JDBC adapter (foundation committed)

- Dedicated `adapter-jdbc-source` Maven module; `adapter-jdbc` remains the Farabi metadata persistence adapter.
- Injected connection factory keeps credential resolution, pooling, driver selection and TLS policy at the runtime boundary.
- Catalog, schema, table/view and column discovery uses JDBC metadata.
- JDBC types map to Farabi logical types while native type names are preserved in metadata.
- Basic dialect identification is present; vendor-specific dialect behavior and PostgreSQL/MySQL Testcontainers contract tests remain to be implemented.
- Limitations: this first executor is synchronous, cannot cancel a query after submission, and retains results only in a bounded process-local cache. It is not a durable job system and must not be advertised as asynchronous or horizontally scalable.
- `ExecutionContextCompilerTest` covers trusted-context propagation, overwriting compiler-supplied context, and source mismatch rejection. This tests the engine boundary, not the Quarkus authentication integration.
- Remaining adapter work: real PostgreSQL/MySQL metadata and query contract tests, vendor-specific dialect behavior, connection pooling/rotation, and a durable worker-backed executor before production async workloads.

### M2-B — Dremio provider alignment (runtime registration implemented)

- Implemented `DremioSourceProvider`, source-scoped `DremioSourceFactory`, and Dremio v3 catalog/schema/table/view/column discovery.
- Registered Dremio in the Quarkus provider registry alongside generic JDBC through `DremioDataSourcePortAdapter`.
- Reuses the persisted workspace-scoped `ConnectionDefinition`; resolves the secret only when an authorized provider operation opens the Dremio source.
- Added unit coverage for opaque-ID navigation, metadata mapping and capability declarations, plus a Quarkus CDI test for both provider registrations.
- Existing Dremio SQL submission, async job status, result paging and cancellation adapters remain wired in the provider source factory.
- Remaining: authenticated source-management endpoints, Numu authorization, live Dremio version compatibility and full remote lifecycle verification.

### M2-C — Runtime connection lifecycle

- Persistent JDBC source-definition storage and initial Quarkus provider composition are committed. Remaining: production Numu authorization, authenticated resource endpoints, connection pooling/rotation lifecycle, audit, and migration automation.
- Complete the Numu contract extraction and tenant/workload enforcement gate above, then implement the authenticated Numu authorization adapter.
- Add authenticated create/list/test/discover/delete endpoints; derive principal, tenant and workspace from the verified identity context.
- Apply migrations automatically or through a documented deployment migration process.
- Add a pooled connection lifecycle keyed by immutable source identity, including secret rotation and bounded connection limits.
- Add source lifecycle audit events and operational health reporting.

### M2-D — End-to-end validation

- Add end-to-end Quarkus tests that prove authenticated identity injection and deny cross-workspace access before metadata or query execution.
- Prove two independent sources can be registered in one workspace through authenticated resource endpoints.
- Prove cross-workspace lookup, discovery and query execution are denied before source lookup or connection open.
- Prove authenticated principal context reaches the governed query use case and compiled plan without client override.
- Verify driver-specific metadata, TLS, read-only principal behavior and type mappings against real PostgreSQL/MySQL instances.

## Deliberately deferred

- Cross-source joins and federation (delegate to Dremio/Trino).
- A home-grown connection pool.
- Automatic credential storage or plaintext secrets.
- Vendor-specific SQL rewriting in the engine.
- Snowflake/BigQuery/Databricks connectors before JDBC and Dremio contracts stabilize.

## Verification status

- Latest GitHub Actions run: [Farabi CI #709](https://github.com/andalus-ai/farabi/actions/runs/43666219990) — **success** on commit `a1ce9d12e500cd682721470db14671ec307d2f76`; the `verify` job completed `mvn --batch-mode --no-transfer-progress verify` successfully across the reactor.
- No local Maven build was run in this environment.
- The branch remains 45 commits ahead and 1 commit behind `main`; inspect/reconcile the divergence before merging or rebasing.
- Do not treat this batch as production-ready: authorization remains deny-by-default, the new registry migration must be applied, and the runtime does not yet pool connections.
