package tech.kayys.andalus.bi.connector;

import org.junit.jupiter.api.Test;
import tech.kayys.andalus.bi.query.QueryExecutor;
import tech.kayys.andalus.bi.query.QueryPlan;
import tech.kayys.andalus.bi.security.AccessContext;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SourceQueryRouterTest {
    private static final class StubConnector implements SourceConnector {
        private final AtomicInteger calls = new AtomicInteger();
        private final ConnectorCapabilities capabilities;

        StubConnector(ConnectorCapabilities capabilities) { this.capabilities = capabilities; }
        @Override public String type() { return "stub"; }
        @Override public ConnectorCapabilities capabilities() { return capabilities; }
        @Override public QueryExecutor.QueryResult execute(SourceDescriptor source, QueryPlan plan, Duration timeout) {
            calls.incrementAndGet();
            return new QueryExecutor.QueryResult(List.of(Map.of("total", 5)));
        }
    }

    private static SourceQueryRouter router(StubConnector connector) {
        var source = new SourceDescriptor("sales", "stub", "secret/sales", "workspace-a");
        return new SourceQueryRouter(new SourceRegistry(List.of(source)),
                new ConnectorRegistry(List.of(connector)));
    }

    private static QueryPlan plan(int limit) {
        return new QueryPlan("sales", "SELECT 5", List.of(), limit);
    }

    @Test void routesToRegisteredConnectorWithinWorkspace() {
        var connector = new StubConnector(new ConnectorCapabilities(true, true, false, false, 100));
        var result = router(connector).execute(new AccessContext("alice", Set.of("workspace-a")),
                "workspace-a", plan(20), Duration.ofSeconds(5));
        assertEquals(5, result.rows().getFirst().get("total"));
        assertEquals(1, connector.calls.get());
    }

    @Test void rejectsCrossWorkspaceAccessBeforeExecution() {
        var connector = new StubConnector(new ConnectorCapabilities(true, true, false, false, 100));
        assertThrows(tech.kayys.andalus.bi.security.AccessDeniedException.class, () ->
                router(connector).execute(new AccessContext("alice", Set.of("workspace-b")),
                        "workspace-a", plan(20), Duration.ofSeconds(5)));
        assertEquals(0, connector.calls.get());
    }

    @Test void enforcesConnectorLimitsAndParameterSupport() {
        var connector = new StubConnector(new ConnectorCapabilities(false, true, false, false, 10));
        assertThrows(IllegalArgumentException.class, () ->
                router(connector).execute(new AccessContext("alice", Set.of("workspace-a")),
                        "workspace-a", plan(5), Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class, () ->
                router(connector).execute(new AccessContext("alice", Set.of("workspace-a")),
                        "workspace-a", plan(20), Duration.ofSeconds(5)));
        assertEquals(0, connector.calls.get());
    }

    @Test void rejectsDuplicateConnectorTypes() {
        var c = new StubConnector(new ConnectorCapabilities(true, true, false, false, 10));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorRegistry(List.of(c, c)));
    }
}
