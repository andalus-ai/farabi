# Operational and security guidance

## Before deployment
1. Apply the PostgreSQL schema in `adapter-jdbc/src/main/resources/db/migration` with your migration runner.
2. Set `FARABI_JDBC_URL`, `FARABI_DB_USER`, `FARABI_DB_PASSWORD`, `FARABI_DREMIO_URL`, `FARABI_DREMIO_TOKEN` via a secret manager.
3. Configure production governance and semantic compiler adapters before enabling query APIs. The foundation deliberately denies submissions by default.
4. Verify TLS, Dremio credentials, database least privilege, logging redaction and tenant isolation.
5. Run `mvn verify` and perform PostgreSQL and Dremio integration testing.

## Observability (target)
Query lifecycle counters, submission latency, provider failures, reconciliation backlog, worker lease churn, audit correlation IDs, per-workspace quota metrics. Do not log raw tokens or sensitive SQL parameters.

## Recovery
`PENDING` after remote uncertainty must not be automatically replayed. Correlate provider job IDs where possible, investigate, then reconcile with an audited operator action.

## Deployment note
`GET /farabi/info` is informational, not a dependency readiness probe. No query execution REST endpoint exists in the foundation.
