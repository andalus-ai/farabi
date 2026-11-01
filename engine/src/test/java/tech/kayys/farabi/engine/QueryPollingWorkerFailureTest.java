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

class QueryPollingWorkerFailureTest {
    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");
    private static final Duration TTL = Duration.ofSeconds(30);

    @Test
    void unavailableLeasePreventsExecutionReadAndProviderCall() {
        UUID id = UUID.randomUUID();
        List<String> calls = new ArrayList<>();
        QueryPollingWorker worker = worker(id, calls, new Provider() {
            public RemoteState status(String job) { calls.add("provider-status"); return new RemoteState(Status.RUNNING, null); }
            public ResultPage results(String job, int offset, int limit) { throw new AssertionError(); }
            public void cancel(String job) { throw new AssertionError(); }
        }, repository(calls, Optional.empty()));

        assertFalse(worker.poll(id, "workspace-a", "worker-2", TTL));
        assertEquals(List.of("claim:workspace-a:worker-2"), calls);
    }

    @Test
    void providerStatusFailureDoesNotCompleteLeaseOrInventTerminalState() {
        UUID id = UUID.randomUUID();
        List<String> calls = new ArrayList<>();
        QueryPollingWorker worker = worker(id, calls, new Provider() {
            public RemoteState status(String job) {
                calls.add("status:" + job);
                throw new IllegalStateException("provider unavailable");
            }
            public ResultPage results(String job, int offset, int limit) { throw new AssertionError(); }
            public void cancel(String job) { throw new AssertionError(); }
        }, repository(calls, Optional.of(new Lease(id, "workspace-a", "worker-1", 4, NOW.plus(TTL)))));

        assertEquals("provider unavailable", assertThrows(IllegalStateException.class,
            () -> worker.poll(id, "workspace-a", "worker-1", TTL)).getMessage());
        assertEquals(List.of("claim:workspace-a:worker-1", "find:workspace-a", "status:remote-7"), calls,
            "failed observation must not be recorded as a terminal transition");
    }

    private static QueryPollingWorker worker(UUID id, List<String> calls, Provider provider,
                                             Repository leases) {
        Execution execution = new Execution(id, "workspace-a", "analyst-1", "toba",
            "remote-7", Status.RUNNING, NOW, NOW);
        QueryPorts.ExecutionRepository executions = new QueryPorts.ExecutionRepository() {
            public void create(Execution ignored) {}
            public Optional<Execution> find(UUID key, String workspace) {
                calls.add("find:" + workspace);
                return key.equals(id) && workspace.equals("workspace-a")
                    ? Optional.of(execution) : Optional.empty();
            }
            public boolean transition(UUID key, String workspace, Status expected, Status next, String remote) {
                calls.add("transition:" + expected + ":" + next);
                return false;
            }
        };
        return new QueryPollingWorker(new WorkerCoordinator(leases, Clock.fixed(NOW, ZoneOffset.UTC)),
            executions, provider);
    }

    private static Repository repository(List<String> calls, Optional<Lease> claim) {
        return new Repository() {
            public Optional<Lease> claim(UUID id, String workspace, String worker, Instant now, Duration ttl) {
                calls.add("claim:" + workspace + ":" + worker);
                return claim;
            }
            public boolean renew(Lease lease, Instant now, Duration ttl) {
                calls.add("renew");
                return true;
            }
            public boolean complete(Lease lease, Status expected, Status next, Instant now) {
                calls.add("complete:" + expected + ":" + next);
                return true;
            }
        };
    }
}
