# Native Farabi chart renderers

The Flutter adapter now ships concrete, dependency-free renderers for `farabi.table` and `farabi.kpi`.

Call `registerFarabiNativeRenderers(registry)` after constructing `FarabiRendererRegistry`. Batik uses the same registry via `registerFarabiBatikDashboard`.

- Table: union of row keys, scrollable DataTable, SQL NULL displayed as an em dash.
- KPI: displays the first row's value at the configured `binding.valueField`. Aggregation must be performed by a governed backend query; the widget never computes untrusted aggregates.
- Empty state is handled by the existing renderer registry before the widget is invoked.

Tenun chart types still require actual Tenun widget implementations and version-specific API verification. This batch does not claim that Tenun integration is complete.
