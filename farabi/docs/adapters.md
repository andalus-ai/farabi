# Farabi P2 — adapter boundary

`farabi-adapter-dremio` implements the engine's `QueryExecutor` outbound port using Dremio REST v3. Only an authorized, compiled `QueryPlan` should reach this adapter. Use `QuerySubmissionService` to enforce authorization before execution.

## Assembly (embedded)

```java
var executor = new DremioQueryExecutor(
    HttpClient.newHttpClient(), new ObjectMapper(),
    URI.create("https://dremio.internal.example"), token);
var service = new QuerySubmissionService(authorizer, semanticCompiler, executor);
```

Production authorization and semantic compiler implementations **must** be supplied by the host application. The adapter does not authenticate end users or enforce SQL governance on its own. The caller must use an allowlisted Dremio URL and securely provision the token. Do not expose direct untrusted SQL compilation or adapter calls through public APIs.

## Follow-ups

- Migrate existing BI policy/semantic compiler implementations behind Farabi ports.
- Add Quarkus CDI producer and configuration.
- Add JDBC job persistence adapter and remote reconciliation.
- Add transport integration tests with a local stub server.

This PR is stacked on P1 (#26) and foundation (#25); no build/tests have been run.
