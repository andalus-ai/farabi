# Batik dashboard transport v1

Farabi publishes renderer-neutral JSON documents. The backend builds `DashboardTransport.Document` from a workspace-scoped manifest and governed data access.

```json
{
  "version": 1,
  "status": "ready",
  "manifestId": "00000000-0000-0000-0000-000000000001",
  "revision": 3,
  "title": "Overview",
  "charts": [
    {
      "id": "revenue",
      "renderer": "tenun.bar",
      "title": "Revenue",
      "definition": {
        "id": "revenue",
        "renderer": "tenun.bar",
        "title": "Revenue",
        "datasetId": "sales",
        "categoryField": "region",
        "valueField": "amount",
        "seriesField": null,
        "filters": [],
        "placement": {"chartId":"revenue","x":0,"y":0,"width":12,"height":3}
      },
      "rows": [{"region":"North","amount":null}]
    }
  ],
  "error": null
}
```

The document supports `ready`, `loading`, and `error` states. Renderer names are Farabi-owned identifiers; Flutter adapters must allowlist them and map to real Tenun APIs.

The JSON is a wire contract, **not** an existing HTTP route. Server-side authentication, dataset policies and endpoint implementation remain required. Avoid exposing detailed authorization failures to unauthenticated callers.
