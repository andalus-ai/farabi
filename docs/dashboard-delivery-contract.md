# Dashboard delivery contract (integration batch 2)

`DashboardDelivery.Document` is a JSON-friendly, renderer-neutral snapshot for a Flutter frontend.

- Carries immutable manifest ID, revision, chart placements, dataset bindings and authorized result rows.
- Each chart has `READY` or `EMPTY` state. Loading and errors belong to the client request lifecycle.
- Preserves SQL NULL values in rows and defensive copies of mutable input maps.
- Rejects missing, duplicated, unexpected or mismatched chart data.

## Batik / Tenun bridge

The frontend should translate `tenun.bar`, `tenun.line`, `tenun.area`, `tenun.pie`, `tenun.scatter` into supported Tenun widgets through an explicit registry; `farabi.table` and `farabi.kpi` may use standard Flutter components. Batik custom component registration is the extension point. Renderer IDs are Farabi wire identifiers, not Dart constructor names.

This module does not yet ship a Flutter package or a production HTTP endpoint. Host-side authentication, data authorization and filter governance remain mandatory. The server must not accept a workspace/subject from untrusted request parameters as authenticated identity.
