# Farabi — hexagonal engine foundation

This is an **additive first slice** of Farabi's architecture. It deliberately lives under `farabi/` so existing root sources and the current BI build are not moved or broken in this PR.

- `engine`: Java 21 domain, application use cases and outbound ports. No Quarkus or persistence dependencies.
- `runtime-core`: embeddable assembly facade accepting caller-provided adapters.
- `runtime-quarkus`: Quarkus-specific HTTP application shell.

Build with `mvn -f farabi/pom.xml test`. Launch Quarkus after dependencies resolve with `mvn -f farabi/runtime-quarkus/pom.xml quarkus:dev` (reactor install may be required).

**Not yet migrated:** existing query jobs, semantic compiler, Dremio gateway, JDBC stores, workers, Tenun and Batik integration. Existing `tech.kayys.andalus.bi` packages are left intact pending a compatibility-aware migration. This initial Quarkus endpoint is a shell, not the production BI API. No build or runtime tests have been executed by the assistant.
