package tech.kayys.farabi.engine;

import static org.junit.jupiter.api.Assertions.*;

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

class QueryPollingWorkerTerminalStateTest {
    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");
    private static final Duration TTL = Duration.ofSeconds(30);

    @Test
    void submittedExecutionReacquiresLeaseBeforeApplyingObservedTerminalState() {
        for (Status terminal : List.of(Status.SUCCEEDED, Status.FAILED, Status.CANCELLED)) {
            List<String> calls = new ArrayList<>();
            UUID id = UUID.randomUUID();
            QueryPollingWorker worker = worker(id, Status.SUBMITTED, terminal, calls);

            assertTrue(worker.poll(id, "workspace-a", "worker-a", TTL), terminal.name());

            assertEquals(List.of(
                "claim:1",
                "complete:SUBMITTED:RUNNING:fence=1",
                "claim:2",
                "complete:RUNNING:" + terminal + ":fence=2"
            ), calls, terminal.name());
        }
    }

    @Test
    void runningExecutionAppliesObservedTerminalStateWithCurrentLease() {
        for (Status terminal : List.of(Status.SUCCEEDED, Status.FAILED, Status.CANCELLED)) {
            List<String> calls = new ArrayList<>();
            UUID id = UUID.randomUUID();
            QueryPollingWorker worker = worker(id, Status.RUNNING, terminal, calls);

            assertTrue(worker.poll(id, "workspace-a", "worker-a", TTL), terminal.name());

            assertEquals(List.of(
                "claim:1",
                "complete:RUNNING:" + terminal + ":fence=1"
            ), calls, terminal.name());
        }
    }

    private static QueryPollingWorker worker(UUID id, Status initial, Status remote,
                                             List<String> calls) {
        Execution execution = new Execution(id, "workspace-a", "analyst-1", "toba",
            "provider-job-1", initial, NOW, NOW);
        QueryPorts.ExecutionRepository executions = new QueryPorts.ExecutionRepository() {
            @Override public void create(Execution ignored) {}
            @Override public Optional<Execution> find(UUID requested, String workspace) {
                return requested.equals(id) && workspace.equals("workspace-a")
                    ? Optional.of(execution) : Optional.empty();
            }
            @Override public boolean transition(UUID requested, String workspace, Status expected,
                                                 Status next, String providerJobId) {
                return false;
            }
        };
        Provider provider = new Provider() {
            @Override public RemoteState status(String providerJobId) {
                assertEquals("provider-job-1", providerJobId);
                return new RemoteState(remote, null);
            }
            @Override public ResultPage results(String providerJobId, int offset, int limit) {
                throw new AssertionError("polling must not fetch results");
            }
            @Override public void cancel(String providerJobId) {
                throw new AssertionError("polling must not request cancellation");
            }
        };
        Repository leases = new Repository() {
            private long fence;
            @Override public Optional<Lease> claim(UUID executionId, String workspace,
                                                   String worker, Instant now, Duration ttl) {
                calls.add("claim:" + (fence + 1));
                return Optional.of(new Lease(executionId, workspace, worker, ++fence, now.plus(ttl)));
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
        return new QueryPollingWorker(
            new WorkerCoordinator(leases, Clock.fixed(NOW, ZoneOffset.UTC)), executions, provider);
    }
}
