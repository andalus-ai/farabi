# Farabi JDBC persistence adapter

`farabi-adapter-jdbc` implements `QueryExecutionRepository` using JDBC and PostgreSQL. It depends on the engine port, not the Quarkus runtime. Supply a configured `DataSource` from any host.

Apply `db/migration/V1__farabi_query_executions.sql` using the host's migration tool before use. The adapter does not run migrations itself.

Status transitions use compare-and-set SQL and a fixed allowlist:
- PENDING -> SUBMITTED, FAILED, CANCELLED
- SUBMITTED -> RUNNING, FAILED, CANCELLED
- RUNNING -> SUCCEEDED, FAILED, CANCELLED

This initial persistence slice is not a replacement for the existing BI fenced worker store: it does not implement leases, fencing epochs, expiry, audit records or workspace/subject authorization. Enforce access control in the application service and use the existing fenced worker mechanism for multi-worker processing until that migration is implemented.
