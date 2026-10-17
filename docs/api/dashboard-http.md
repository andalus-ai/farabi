# Dashboard HTTP adapter (P1 continuation)

This is a **library HTTP handler**, not a deployed service. Register it on an HTTP server at `/api/v1/workspaces/` after providing a trusted `TokenAuthenticator`, `AuthorizedDashboardService`, and a workspace-scoped semantic metric resolver.

## Routes

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/v1/workspaces/{workspace}/dashboards` | Create dashboard (201) |
| GET | `/api/v1/workspaces/{workspace}/dashboards/{id}` | Latest version (200/404) |
| PUT | `/api/v1/workspaces/{workspace}/dashboards/{id}` | Append version (200/409) |
| GET | `/api/v1/workspaces/{workspace}/dashboards/{id}/versions` | Version numbers (200) |

POST JSON accepts `title`, `description`, and `widgets`. PUT also requires `expectedVersion`. Widget JSON uses the `WidgetSpec` record fields `id`, `title`, `kind`, `metricId`, `dimensions`, `options`.

## Authentication and security

The handler rejects missing Bearer tokens with 401 and workspace scope mismatches with 403. The supplied authenticator **must** verify the token signature, issuer, audience, expiry, and trusted workspace membership. Never trust a user-supplied workspace membership claim without validation.

The metric resolver must return only metrics authorized for that workspace and actor. The current resolver signature accepts a workspace ID only and therefore cannot express per-user metric authorization; do not expose sensitive metrics through this preliminary adapter.

Production requirements not yet implemented: HTTPS termination, an identity-provider integration, fine-grained RBAC, request rate limits, structured audit logging, secure error telemetry, JSON schema versioning, and API integration tests.

## Response codes

- 201: dashboard created
- 200: dashboard fetched or updated
- 400: malformed input
- 401: unauthenticated
- 403: workspace access denied
- 404: dashboard not found
- 409: optimistic version conflict
- 500: unexpected internal error

## Verification

No live server or identity provider is configured by this PR. Unit tests and integration tests must be run before merge.
