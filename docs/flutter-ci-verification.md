# Flutter verification

This branch adds a CI workflow for both Flutter packages:

- `flutter/farabi_dashboard`
- `flutter/farabi_tenun`

CI installs Flutter stable, resolves dependencies, checks Dart formatting, runs static analysis and executes tests.

## Local commands

```sh
cd flutter/farabi_dashboard
flutter pub get
dart format lib test
flutter analyze
flutter test
cd ../farabi_tenun
flutter pub get
dart format lib test
flutter analyze
flutter test
```

This workflow has been committed but no successful workflow run is claimed. If CI finds failures, correct them on this branch before opening a PR.

Tenun documentation describes `TenunChart(jsonConfig:, width:, height:)` and `coreChartsBundle.register()`; pie/scatter shape validation still requires tests against the resolved Tenun release.
