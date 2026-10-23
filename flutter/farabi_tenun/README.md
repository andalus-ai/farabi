# Farabi + Tenun concrete widget integration

This package uses the public `tenun: 0.1.0` package and its documented `TenunChart(jsonConfig:, width:, height:)` API.

Call `initializeFarabiTenun()` once at app bootstrap to register Tenun's core chart family, then `registerFarabiTenunRenderers(registry)` to connect bar, line, area, pie and scatter widgets.

The adapter aligns category labels across series, retains missing values as null, and rejects duplicate category/series coordinates instead of silently aggregating.

**Verification needed:** Run `flutter pub get && flutter test` with a Flutter SDK. In particular, confirm Tenun's pie and scatter JSON shape requirements against actual runtime validation. The current converter uses the documented common `series` schema, which may require chart-specific refinements.

For production, lock dependencies and confirm all five chart families with golden and integration tests.
