# Tenun widget factory boundary

`registerFarabiTenunAdapters` registers all five Farabi chart renderer IDs with an explicit widget factory per chart type. It performs chart binding, numeric conversion, grouping and no-plottable-data handling before invoking the factory.

The host Flutter app supplies real Tenun widgets in `bar`, `line`, `area`, `pie`, and `scatter` factories. This avoids hardcoding unverified Tenun constructors or imposing a single Tenun version on the engine.

**Not yet complete:** The factory signatures are ready, but this repository still does not instantiate Tenun package widgets. Before release, add a separate pinned `farabi_tenun` package using verified public Tenun APIs, run Flutter tests, and add golden tests for each chart type.
