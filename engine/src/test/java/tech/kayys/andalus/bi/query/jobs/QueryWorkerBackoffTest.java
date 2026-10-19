package tech.kayys.andalus.bi.query.jobs;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class QueryWorkerBackoffTest {
    @Test void doublesUntilCapped() {
        var backoff = new QueryWorkerBackoff(Duration.ofSeconds(2), Duration.ofSeconds(10));
        assertEquals(Duration.ofSeconds(2), backoff.delay(0));
        assertEquals(Duration.ofSeconds(4), backoff.delay(1));
        assertEquals(Duration.ofSeconds(8), backoff.delay(2));
        assertEquals(Duration.ofSeconds(10), backoff.delay(3));
        assertEquals(Duration.ofSeconds(10), backoff.delay(1000));
    }

    @Test void rejectsInvalidInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> new QueryWorkerBackoff(Duration.ZERO, Duration.ofSeconds(1)));
        var backoff = new QueryWorkerBackoff(Duration.ofSeconds(1), Duration.ofSeconds(5));
        assertThrows(IllegalArgumentException.class, () -> backoff.delay(-1));
    }
}
