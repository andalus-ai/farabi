# Flutter dashboard loading (consolidated batch)

The Farabi Flutter package now includes a transport client and request-scoped widget:

- `FarabiDashboardClient` fetches JSON dashboard delivery snapshots using an injected `http.Client`.
- `FarabiRemoteDashboardView` provides loading, error, retry and successful rendering states.
- Non-local endpoints require HTTPS; 401/403 and 404 errors do not expose server response bodies.
- Widget reloads when its manifest ID or client changes.
- Mock HTTP tests cover successful delivery, forbidden access and transport URL validation.

## Required backend integration

The client assumes a **future** authenticated endpoint:

`GET /api/dashboards/{manifestId}/delivery`

This endpoint is **not implemented** by this batch. The Quarkus adapter must derive workspace and subject from authenticated identity, call `DashboardRuntime.load`, then serialize `DashboardDelivery.from(...)`. Do not accept identity via query parameters or untrusted headers.

Pass an authenticated `http.Client` from the host application; the package intentionally does not manage access tokens. The Flutter Tenun chart constructors are still pending API validation.
