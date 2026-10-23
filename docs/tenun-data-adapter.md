# Tenun data preparation

This batch adds `FarabiTenunDataAdapter` to the Flutter package.

The adapter maps authenticated Farabi dashboard rows into typed, ordered `FarabiSeriesPoint` values and optional named series. It skips NULL/non-finite values, preserves original row positions, does not aggregate duplicate categories, and does not execute expressions or SQL.

This is the **data boundary**, not the Tenun widget implementation. Specific Tenun constructors, chart data models, and package versions must be verified before binding these points to `tenun.bar`, `tenun.line`, `tenun.area`, `tenun.pie`, and `tenun.scatter`.

The server remains responsible for authorization, aggregation and row/column governance.
