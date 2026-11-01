package tech.kayys.farabi.engine;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.QueryContracts.*;
import tech.kayys.farabi.engine.QueryLifecycle.*;
import tech.kayys.farabi.engine.WorkerLeases.*;

class QueryPollingWorkerInFlightLeaseExpiryTest {
    @Test
    void lateProviderObservationCannotOverwriteReplacementWorker() throws Exception {
        Instant start = Instant.parse("2026-11-01T00:00:00Z");
        Duration ttl = Duration.ofSeconds(30);
        UUID id = UUID.randomUUID();
        MutableClock clock = new MutableClock(start);
        AtomicReference<Execution> state = new AtomicReference<>(new Execution(
            id, "workspace-a", "analyst", "toba", "job-1", Status.RUNNING, start, start));
        FencedLeases leases = new FencedLeases(state);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();

        Provider provider = new Provider() {
            public RemoteState status(String job) {
                if (calls.incrementAndGet() == 1) {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("provider wait timed out");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    return new RemoteState(Status.FAILED, "late response");
                }
                return new RemoteState(Status.SUCCEEDED, null);
            }
            public ResultPage results(String job, int offset, int limit) { throw new AssertionError(); }
            public void cancel(String job) { throw new AssertionError(); }
        };
        QueryPorts.ExecutionRepository executions = new QueryPorts.ExecutionRepository() {
            public void create(Execution e) { throw new AssertionError(); }
            public Optional<Execution> find(UUID key, String workspace) {
                return key.equals(id) && workspace.equals("workspace-a")
                    ? Optional.of(state.get()) : Optional.empty();
            }
            public boolean transition(UUID key, String workspace, Status from, Status to, String job) {
                throw new AssertionError("must use fenced lease completion");
            }
        };
        QueryPollingWorker worker = new QueryPollingWorker(
            new WorkerCoordinator(leases, clock), executions, provider);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> stale = pool.submit(() -> worker.poll(id, "workspace-a", "worker-a", ttl));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            clock.advance(ttl.plusSeconds(1));

            assertTrue(worker.poll(id, "workspace-a", "worker-b", ttl));
            assertEquals(Status.SUCCEEDED, state.get().status());
            assertEquals(2, leases.fence());

            release.countDown();
            assertFalse(stale.get(5, TimeUnit.SECONDS));
            assertEquals(Status.SUCCEEDED, state.get().status());
            assertEquals(2, leases.fence());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private static final class FencedLeases implements Repository {
        private final AtomicReference<Execution> state;
        private Lease active;
        private long fence;
        FencedLeases(AtomicReference<Execution> state) { this.state = state; }
        public synchronized Optional<Lease> claim(UUID id, String ws, String worker, Instant now, Duration ttl) {
            Execution e = state.get();
            if (!id.equals(e.id()) || !ws.equals(e.workspaceId())
                || (e.status() != Status.SUBMITTED && e.status() != Status.RUNNING)
                || (active != null && now.isBefore(active.expiresAt()))) return Optional.empty();
            active = new Lease(id, ws, worker, ++fence, now.plus(ttl));
            return Optional.of(active);
        }
        public synchronized boolean renew(Lease lease, Instant now, Duration ttl) {
            if (!owns(lease, now)) return false;
            active = new Lease(lease.executionId(), lease.workspaceId(), lease.workerId(),
                lease.fence(), now.plus(ttl));
            return true;
        }
        public synchronized boolean complete(Lease lease, Status from, Status to, Instant now) {
            if (!owns(lease, now) || state.get().status() != from) return false;
            Execution e = state.get();
            state.set(new Execution(e.id(), e.workspaceId(), e.subjectId(), e.sourceId(),
                e.providerJobId(), to, e.createdAt(), now));
            active = null;
            return true;
        }
        private boolean owns(Lease l, Instant now) {
            return active != null && active.fence() == l.fence()
                && active.workerId().equals(l.workerId())
                && active.executionId().equals(l.executionId())
                && active.workspaceId().equals(l.workspaceId())
                && now.isBefore(active.expiresAt());
        }
        synchronized long fence() { return fence; }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;
        MutableClock(Instant now) { this.now = new AtomicReference<>(now); }
        void advance(Duration d) { now.updateAndGet(t -> t.plus(d)); }
        public Instant instant() { return now.get(); }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
    }
}
