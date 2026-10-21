package tech.kayys.farabi.engine;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import tech.kayys.farabi.engine.QueryContracts.*;
import tech.kayys.farabi.engine.QueryLifecycle.*;
import tech.kayys.farabi.engine.WorkerLeases.*;

/**
 * One bounded poll iteration. A scheduler supplies execution IDs and workspace context.
 * Never retries remote submissions; only observes known provider job IDs.
 */
public final class QueryPollingWorker {
    private final WorkerCoordinator coordinator;
    private final QueryPorts.ExecutionRepository executions;
    private final Provider provider;
    public QueryPollingWorker(WorkerCoordinator coordinator,QueryPorts.ExecutionRepository executions,Provider provider) {
        this.coordinator=Objects.requireNonNull(coordinator);
        this.executions=Objects.requireNonNull(executions);
        this.provider=Objects.requireNonNull(provider);
    }
    public boolean poll(UUID id,String workspace,String workerId,Duration ttl) {
        var lease=coordinator.claim(id,workspace,workerId,ttl);
        if(lease.isEmpty()) return false;
        Lease current=lease.get();
        Execution execution=executions.find(id,workspace).orElseThrow(()->new IllegalStateException("leased execution missing"));
        if(execution.providerJobId()==null) throw new IllegalStateException("leased execution missing provider job");
        RemoteState remote=provider.status(execution.providerJobId());
        if(remote.status()==Status.RUNNING) {
            if(execution.status()==Status.SUBMITTED) return coordinator.complete(current,Status.SUBMITTED,Status.RUNNING);
            return coordinator.renew(current,ttl);
        }
        if(remote.status()!=Status.SUCCEEDED&&remote.status()!=Status.FAILED&&remote.status()!=Status.CANCELLED)
            throw new IllegalStateException("unexpected remote status");
        if(execution.status()==Status.SUBMITTED) {
            // Provider may finish before first poll; transition to RUNNING first.
            if(!coordinator.complete(current,Status.SUBMITTED,Status.RUNNING)) return false;
            // Acquire a fresh fence before terminal transition.
            return coordinator.claim(id,workspace,workerId,ttl)
                .map(next->coordinator.complete(next,Status.RUNNING,remote.status())).orElse(false);
        }
        return coordinator.complete(current,Status.RUNNING,remote.status());
    }
}
