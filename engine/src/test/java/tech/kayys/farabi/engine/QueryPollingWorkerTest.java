package tech.kayys.farabi.engine;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.QueryContracts.*;
import tech.kayys.farabi.engine.QueryLifecycle.*;
import tech.kayys.farabi.engine.WorkerLeases.*;

import static org.junit.jupiter.api.Assertions.*;

class QueryPollingWorkerTest {
    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");
    private static final Duration TTL = Duration.ofSeconds(30);

    @Test
    void ambiguousPendingExecutionWithoutProviderIdNeverCallsProvider() {
        UUID id = UUID.randomUUID();
        List<String> providerCalls = new ArrayList<>();
        List<String> leaseCalls = new ArrayList<>();
        QueryPollingWorker worker = worker(id, execution(id, null, Status.PENDING),
            provider(providerCalls), leaseRepository(leaseCalls));

        assertThrows(IllegalStateException.class,
            () -> worker.poll(id, "workspace-a", "worker-1", TTL));

        assertEquals(List.of("claim:workspace-a:worker-1"), leaseCalls);
        assertTrue(providerCalls.isEmpty(),
            "an ambiguous submission must not be polled without a provider job ID");
    }

    @Test
    void submittedExecutionWithRunningProviderStateTransitionsToRunning() {
        UUID id = UUID.randomUUID();
        List<String> providerCalls = new ArrayList<>();
        List<String> leaseCalls = new ArrayList<>();
        QueryPollingWorker worker = worker(id, execution(id, "remote-42", Status.SUBMITTED),
            provider(providerCalls), leaseRepository(leaseCalls));

        assertTrue(worker.poll(id, "workspace-a", "worker-1", TTL));

        assertEquals(List.of("status:remote-42"), providerCalls);
        assertEquals(List.of(
            "claim:workspace-a:worker-1",
            "complete:SUBMITTED:RUNNING:fence=1"), leaseCalls);
    }

    private static QueryPollingWorker worker(UUID id, Execution execution, Provider provider,
                                             Repository leases) {
        QueryPorts.ExecutionRepository executions = new QueryPorts.ExecutionRepository() {
            @Override public void create(Execution ignored) {}
            @Override public Optional<Execution> find(UUID requestedId, String workspace) {
                return requestedId.equals(id) && workspace.equals("workspace-a")
                    ? Optional.of(execution) : Optional.empty();
            }
            @Override public boolean transition(UUID requestedId, String workspace, Status expected,
                                                 Status next, String providerJobId) {
                return false;
            }
        };
        return new QueryPollingWorker(new WorkerCoordinator(leases,
            Clock.fixed(NOW, ZoneOffset.UTC)), executions, provider);
    }

    private static Execution execution(UUID id, String providerJobId, Status status) {
        return new Execution(id, "workspace-a", "analyst-1", "toba", providerJobId,
            status, NOW, NOW);
    }

    private static Provider provider(List<String> calls) {
        return new Provider() {
            @Override public RemoteState status(String providerJobId) {
                calls.add("status:" + providerJobId);
                return new RemoteState(Status.RUNNING, null);
            }
            @Override public ResultPage results(String providerJobId, int offset, int limit) {
                calls.add("results:" + providerJobId);
                return new ResultPage(List.of(), List.of(), offset, false);
            }
            @Override public void cancel(String providerJobId) {
                calls.add("cancel:" + providerJobId);
            }
        };
    }

    private static Repository leaseRepository(List<String> calls) {
        return new Repository() {
            @Override public Optional<Lease> claim(UUID id, String workspace, String worker,
                                                   Instant now, Duration ttl) {
                calls.add("claim:" + workspace + ":" + worker);
                return Optional.of(new Lease(id, workspace, worker, 1, now.plus(ttl)));
            }
            @Override public boolean renew(Lease lease, Instant now, Duration ttl) {
                calls.add("renew:fence=" + lease.fence());
                return true;
            }
            @Override public boolean complete(Lease lease, Status expected, Status next, Instant now) {
                calls.add("complete:" + expected + ":" + next + ":fence=" + lease.fence());
                return true;
            }
        };
    }
}
