# Toba / Dremio query transport — initial implementation

This increment introduces a real HTTP transport class for Dremio's REST v3 SQL job flow, with JDK HttpClient and Jackson. It does **not** create a Toba API, connect to a live Dremio deployment, or make raw SQL safe for untrusted clients.

`DremioHttpGateway` supports:
- `POST /api/v3/sql` to submit an already-authorized SQL statement.
- `GET /api/v3/job/{id}` to inspect job status.
- `GET /api/v3/job/{id}/results?offset=&limit=` for paginated results.
- Dremio access token via a server-side supplier.
- HTTPS-only remote endpoints, per-request timeouts, bounded result pages, and job ID validation.

**Security:** Never call `submitTrustedSql` directly from HTTP request handlers or AI tools. The current method accepts SQL text but does not prove it came from an authorized semantic query compiler. Introduce a validated, actor-bound query service before exposing it. Query execution must use a least-privileged Dremio principal, dataset authorization and masking policy checks, plus result/audit isolation. This adapter does not implement those controls.

**Limitations:** The Dremio REST v3 endpoint shapes and `_dremio<token>` header format must be checked against the target Dremio version in deployment. No live integration verification was performed. HTTP redirects, network egress restrictions, token rotation, cancellation, idempotency, query budgets, result-schema normalization, streaming, or source-specific SQL dialect adaptation are implemented yet. The transport currently buffers responses in memory before applying its 4 MiB size check; use a streaming bounded body subscriber in production.

**Tests:** JUnit tests use a loopback HTTP server to exercise submission, result paging, authentication header, invalid job IDs, and error redaction. Run `mvn test` locally or in CI before merging.
