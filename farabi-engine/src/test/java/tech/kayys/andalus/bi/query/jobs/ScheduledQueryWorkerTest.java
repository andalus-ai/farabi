package tech.kayys.andalus.bi.query.jobs;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ScheduledQueryWorkerTest {
    @Test void runsBoundedPollingAndStops() throws Exception {
        CountDownLatch called = new CountDownLatch(1);
        var dispatcher = new QueryJobWorkerDispatcher(
            limit -> List.of(UUID.randomUUID()),
            (id, workerId, ttl) -> new JdbcFencedJobWorkerStore.Lease(
                id, workerId, 1L, java.time.Instant.now().plus(ttl)));
        var worker = new ScheduledQueryWorker(dispatcher, lease -> called.countDown(),
            "worker-test", Duration.ofSeconds(10), 1);
        try {
            worker.start(Duration.ofMillis(20));
            assertTrue(called.await(2, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> worker.start(Duration.ofSeconds(1)));
        } finally { worker.close(); }
        assertThrows(IllegalStateException.class, () -> worker.start(Duration.ofSeconds(1)));
    }

    @Test void rejectsInvalidConfiguration() {
        var dispatcher = new QueryJobWorkerDispatcher(limit -> List.of(),
            (id, workerId, ttl) -> null);
        assertThrows(IllegalArgumentException.class, () ->
            new ScheduledQueryWorker(dispatcher, lease -> {}, "worker",
                Duration.ofSeconds(1), 0));
    }
}
