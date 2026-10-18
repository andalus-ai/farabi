# Governed Dremio query execution

Next integration increment: verify actor authorization, use an authorized semantic catalog, and compile a restricted query before submitting it to Dremio. Dremio REST SQL transport does not provide JDBC parameter binding, so filtered queries must remain disabled until a safe Dremio dialect implementation is available.

Query job ownership must be persisted server-side; a client-provided job ID cannot establish authorization. Production integration requires Toba identity and governance checks, plus end-to-end Dremio tests.
