# P2 scheduled query worker runtime

`ScheduledQueryWorker` invokes `QueryJobWorkerDispatcher.runOnce` at a fixed delay using a single daemon thread. It prevents duplicate starts, validates configuration, and supports shutdown.

## Wiring example

```java
var dispatcher = new QueryJobWorkerDispatcher(scanner::scan, leaseStore::claim);
var worker = new ScheduledQueryWorker(dispatcher, lease -> {
    // Trusted handler: fetch persisted job, decide recovery action,
    // reconcile remote status, then commit using fenced transition.
}, "node-1-worker-1", Duration.ofSeconds(30), 100);
worker.start(Duration.ofSeconds(5));
```

## Not yet production ready

- This is **process-local** scheduling; it relies on PostgreSQL fencing for competing processes.
- The provided handler must enforce job ownership, valid transitions and fenced database updates.
- Lease renewal, release, persisted retry timing, cancellation, remote idempotency and metrics are not wired.
- Exceptions are currently swallowed to preserve scheduling. Production deployments need structured failure reporting.
- Closing cancels future polling but does not interrupt a running handler. Shutdown requires bounded handler execution.
- Unit tests are committed but have not been run in this session.
