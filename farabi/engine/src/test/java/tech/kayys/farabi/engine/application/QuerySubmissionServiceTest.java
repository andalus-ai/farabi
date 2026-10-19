package tech.kayys.farabi.engine.application;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.domain.*;
import static org.junit.jupiter.api.Assertions.*;

class QuerySubmissionServiceTest {
    private final QueryRequest request = new QueryRequest("w", "u", "toba", "sales_by_region", Map.of());

    @Test void authorizesBeforeCompilingAndSubmitting() {
        var steps = new StringBuilder();
        var service = new QuerySubmissionService(
            r -> steps.append("A"),
            r -> { steps.append("C"); return new QueryPlan("toba", "select 1"); },
            p -> { steps.append("E"); return new QuerySubmission("remote-1"); });
        assertEquals("remote-1", service.submit(request).providerJobId());
        assertEquals("ACE", steps.toString());
    }

    @Test void denialNeverExecutes() {
        var calls = new AtomicInteger();
        var service = new QuerySubmissionService(
            r -> { throw new SecurityException("denied"); },
            r -> { calls.incrementAndGet(); return new QueryPlan("toba", "select 1"); },
            p -> { calls.incrementAndGet(); return new QuerySubmission("remote-1"); });
        assertThrows(SecurityException.class, () -> service.submit(request));
        assertEquals(0, calls.get());
    }

    @Test void sourceSwitchNeverExecutes() {
        var calls = new AtomicInteger();
        var service = new QuerySubmissionService(
            r -> {},
            r -> new QueryPlan("different-source", "select 1"),
            p -> { calls.incrementAndGet(); return new QuerySubmission("remote-1"); });
        assertThrows(IllegalStateException.class, () -> service.submit(request));
        assertEquals(0, calls.get());
    }
}
