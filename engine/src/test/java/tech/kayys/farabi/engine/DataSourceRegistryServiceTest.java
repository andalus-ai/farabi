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
                "jdbc-test", URI.create("jdbc:postgresql://localhost:5432/db"), null, null, Map.of(),
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


    @Test
    void delegatesDiscoveryToProviderOnlyAfterWorkspaceAuthorization() {
        var registry = new InMemoryRegistry();
        registry.register(definition());
        var provider = new Provider() {
            @Override public String providerKey() { return "jdbc-test"; }
            @Override public Capabilities capabilities(ConnectionDefinition d) {
                return new Capabilities(Set.of(Feature.SCHEMAS), 1, 1);
            }
            @Override public SourceHealth testConnection(ConnectionDefinition d) {
                return new SourceHealth(Health.AVAILABLE, Instant.EPOCH, null);
            }
            @Override public List<String> catalogs(ConnectionDefinition d) {
                return List.of("warehouse");
            }
            @Override public List<String> schemas(ConnectionDefinition d, String catalog) {
                return List.of("public");
            }
            @Override public List<CatalogEntry> datasets(
                    ConnectionDefinition d, String catalog, String schema) {
                return List.of(new CatalogEntry("warehouse.public.orders", "orders", EntryKind.TABLE));
            }
            @Override public List<ColumnMetadata> columns(
                    ConnectionDefinition d, String catalog, String schema, String dataset) {
                return List.of(new ColumnMetadata("id", LogicalType.INTEGER, false));
            }
        };
        var actions = new ArrayList<Action>();
        var service = new DataSourceRegistryService(registry,
                key -> "jdbc-test".equals(key) ? Optional.of(provider) : Optional.empty(),
                (w, subject, id, action) -> {
                    assertEquals("workspace-a", w);
                    assertEquals("alice", subject);
                    actions.add(action);
                });

        assertEquals(Health.AVAILABLE,
                service.testConnection("sales", "workspace-a", "alice").health());
        assertEquals(List.of("warehouse"), service.catalogs("sales", "workspace-a", "alice"));
        assertEquals(1, service.datasets("sales", "workspace-a", "warehouse", "public", "alice").size());
        assertEquals("id", service.columns(
                "sales", "workspace-a", "warehouse", "public", "orders", "alice").getFirst().name());
        assertEquals(List.of(Action.TEST, Action.DISCOVER, Action.DISCOVER, Action.DISCOVER), actions);
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
