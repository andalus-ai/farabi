package tech.kayys.farabi.engine;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.DataSourceContracts.*;
import tech.kayys.farabi.engine.DataSourcePorts.*;

class DataSourceRegistryServiceTest {
    private ConnectionDefinition definition() {
        return new ConnectionDefinition("sales", "workspace-a", "Sales", ProviderKind.JDBC,
                "jdbc-test", URI.create("jdbc:test://localhost/db"), null, null, Map.of(),
                null, true, Instant.EPOCH);
    }

    @Test
    void authorizesBeforeRegisteringAndRejectsUnknownProvider() {
        var registry = new InMemoryRegistry();
        var service = new DataSourceRegistryService(registry, key -> Optional.empty(),
                (w, s, id, action) -> assertEquals(Action.REGISTER, action));
        assertThrows(IllegalArgumentException.class, () -> service.register(definition(), "alice"));
        assertTrue(registry.sources.isEmpty());
    }

    @Test
    void authorizesDiscoveryBeforeLookingUpWorkspaceScopedSource() {
        var registry = new InMemoryRegistry();
        registry.register(definition());
        var calls = new ArrayList<Action>();
        var service = new DataSourceRegistryService(registry, key -> Optional.empty(),
                (w, s, id, action) -> calls.add(action));
        assertThrows(IllegalArgumentException.class,
                () -> service.testConnection("sales", "workspace-b", "alice"));
        assertEquals(List.of(Action.TEST), calls);
    }

    private static final class InMemoryRegistry implements ConnectionRegistry {
        private final Map<String, ConnectionDefinition> sources = new HashMap<>();
        @Override public boolean register(ConnectionDefinition d) {
            return sources.putIfAbsent(d.workspaceId() + "/" + d.sourceId(), d) == null;
        }
        @Override public Optional<ConnectionDefinition> find(String id, String workspace) {
            return Optional.ofNullable(sources.get(workspace + "/" + id));
        }
        @Override public List<ConnectionDefinition> list(String workspace) {
            return sources.values().stream().filter(d -> d.workspaceId().equals(workspace)).toList();
        }
        @Override public boolean remove(String id, String workspace) {
            return sources.remove(workspace + "/" + id) != null;
        }
    }
}
