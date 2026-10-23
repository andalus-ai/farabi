# Quarkus dashboard delivery endpoint

`GET /api/dashboards/{manifestId}/delivery` returns the Java `DashboardDelivery.Document` JSON contract consumed by the Flutter client.

## Integration requirements

The host application **must** supply:

1. A CDI bean implementing `DashboardDeliveryResource.AuthenticatedContext`. It must derive workspace and subject from validated credentials/session, not request headers supplied by the caller.
2. Exactly one CDI bean implementing `DashboardRuntime.DatasetAccess`, enforcing workspace, dataset and subject policy before fetching governed rows.
3. A configured PostgreSQL `DataSource` with manifest migration V4 applied.

The endpoint does not accept a workspace ID or subject ID as an HTTP parameter. Missing adapters fail closed; do not expose a permissive development identity adapter in production.

## Response behavior

- 200: dashboard delivery JSON
- 400: invalid manifest identifier
- 401: missing/invalid authentication
- 403: dataset authorization denied
- 404: manifest unavailable in the authenticated workspace
- 503: required integration unavailable

Backend authorization is mandatory. Visualization filters are metadata and must not be confused with governed SQL predicates.

## Known limitations

This integration has not been verified with live Quarkus, PostgreSQL, or the Flutter client. The endpoint currently uses a fixed 1,000-row limit and is synchronous; production deployment needs pagination, cancellation, request timeouts, output-size limits, and observability. The host must configure Quarkus REST JSON serialization and identity integration. Real Tenun rendering remains separate.
