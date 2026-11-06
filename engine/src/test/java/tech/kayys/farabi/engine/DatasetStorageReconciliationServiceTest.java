package tech.kayys.farabi.engine;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.DatasetStorageReconciliationPorts.*;

class DatasetStorageReconciliationServiceTest {
    @Test void inspectUsesClockAndReturnsPersistedReport() {
        Instant now=Instant.parse("2026-11-01T00:00:00Z");
        Report expected=new Report(UUID.randomUUID(),"workspace-a",now,1000,300,300,20,20,20,List.of());
        Store store=new Store() {
            public Report inspect(String workspace,Instant at) {
                assertEquals("workspace-a",workspace); assertEquals(now,at); return expected;
            }
            public List<Report> recent(String workspace,int limit) { return List.of(expected); }
        };
        DatasetStorageReconciliationService service=new DatasetStorageReconciliationService(
                store,Clock.fixed(now,ZoneOffset.UTC));
        assertSame(expected,service.inspect("workspace-a"));
        assertEquals(List.of(expected),service.recent("workspace-a",5));
    }

    @Test void rejectsInvalidWorkspaceAndHistoryLimit() {
        Store store=new Store() {
            public Report inspect(String workspace,Instant at) { throw new AssertionError(); }
            public List<Report> recent(String workspace,int limit) { throw new AssertionError(); }
        };
        DatasetStorageReconciliationService service=new DatasetStorageReconciliationService(store,Clock.systemUTC());
        assertThrows(IllegalArgumentException.class,()->service.inspect(" "));
        assertThrows(IllegalArgumentException.class,()->service.recent("workspace-a",101));
    }
}
