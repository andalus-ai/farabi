# Farabi Flutter dashboard adapter

Initial Batik integration for the Farabi DashboardDelivery v1 wire contract.

- Parses dashboard documents, including nullable chart data.
- Renders 12-column grid layouts in Flutter.
- Registers `farabi.dashboard` as a Batik `CustomNode` using `UIComponentRegistry.registerCustom`.
- Exposes an allowlisted chart renderer registry for Tenun widget builders.
- Supports chart-level empty and unavailable states.

Actual Tenun widget constructors, authenticated HTTP, responsive mobile layouts, loading/error handling, and end-to-end testing remain outstanding. Pin Batik to a tested Git commit before production.

Run `flutter test` inside this package after resolving dependencies.
