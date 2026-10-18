package tech.kayys.andalus.bi.query.jobs;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class QueryJobWorkerDispatcherTest {
    @Test void claimsAndProcessesOnlyOwnedJobs() {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        var invoked = new AtomicInteger();
        var dispatcher = new QueryJobWorkerDispatcher(
            limit -> List.of(first, second),
            (id, worker, ttl) -> id.equals(first)
                ? new JdbcFencedJobWorkerStore.Lease(id, worker, 1L, Instant.now().plus(ttl))
                : null
        );
        var result = dispatcher.runOnce("worker-1", Duration.ofSeconds(30), 10,
            lease -> invoked.incrementAndGet());
        assertEquals(2, result.discovered());
        assertEquals(1, result.claimed());
        assertEquals(1, result.processed());
        assertEquals(0, result.failed());
        assertEquals(1, invoked.get());
    }

    @Test void countsHandlerFailuresAndContinues() {
        var dispatcher = new QueryJobWorkerDispatcher(
            limit -> List.of(UUID.randomUUID(), UUID.randomUUID()),
            (id, worker, ttl) -> new JdbcFencedJobWorkerStore.Lease(
                id, worker, 2L, Instant.now().plus(ttl))
        );
        var count = new AtomicInteger();
        var result = dispatcher.runOnce("worker-1", Duration.ofSeconds(30), 2, lease -> {
            if (count.incrementAndGet() == 1) throw new IllegalStateException("failed");
        });
        assertEquals(2, result.claimed());
        assertEquals(1, result.failed());
        assertEquals(1, result.processed());
    }

    @Test void rejectsInvalidLimits() {
        var dispatcher = new QueryJobWorkerDispatcher(
            limit -> List.of(), (id, worker, ttl) -> null);
        assertThrows(IllegalArgumentException.class, () ->
            dispatcher.runOnce("worker-1", Duration.ofSeconds(30), 0, lease -> {}));
    }
}
