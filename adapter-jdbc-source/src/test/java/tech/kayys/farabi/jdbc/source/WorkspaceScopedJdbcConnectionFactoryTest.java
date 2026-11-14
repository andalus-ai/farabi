package tech.kayys.farabi.jdbc.source;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.DataSourceContracts.*;
import tech.kayys.farabi.engine.DataSourcePorts.*;

class WorkspaceScopedJdbcConnectionFactoryTest {
    private static ConnectionDefinition source(String workspace, boolean readOnly) {
        return new ConnectionDefinition("sales", workspace, "Sales", ProviderKind.JDBC, "jdbc",
                URI.create("jdbc:h2:mem:sales"), null, null, Map.of(), null, readOnly, Instant.now());
    }
    private static Connection connection() {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> null);
    }

    @Test void authorizesThenResolvesOnlyWithinRequestedWorkspace() throws Exception {
        ConnectionDefinition definition = source("workspace-a", true);
        AtomicBoolean authorized = new AtomicBoolean();
        AtomicBoolean opened = new AtomicBoolean();
        ConnectionRegistry registry = new ConnectionRegistry() {
            public boolean register(ConnectionDefinition d) { return false; }
            public Optional<ConnectionDefinition> find(String id, String workspace) {
                assertTrue(authorized.get());
                return id.equals("sales") && workspace.equals("workspace-a") ? Optional.of(definition) : Optional.empty();
            }
            public List<ConnectionDefinition> list(String workspace) { return List.of(); }
            public boolean remove(String id, String workspace) { return false; }
        };
        var factory = new WorkspaceScopedJdbcConnectionFactory(registry,
                (workspace, subject, source, action) -> {
                    assertEquals("workspace-a", workspace); assertEquals("analyst", subject);
                    assertEquals("sales", source); assertEquals(Action.QUERY, action);
                    authorized.set(true);
                },
                d -> { opened.set(true); assertSame(definition, d); return connection(); });

        assertNotNull(factory.open("sales", "workspace-a", "analyst"));
        assertTrue(opened.get());
    }

    @Test void deniesBeforeLookupWhenAuthorizationFails() {
        AtomicBoolean lookedUp = new AtomicBoolean();
        ConnectionRegistry registry = new ConnectionRegistry() {
            public boolean register(ConnectionDefinition d) { return false; }
            public Optional<ConnectionDefinition> find(String id, String workspace) {
                lookedUp.set(true); return Optional.of(source(workspace, true));
            }
            public List<ConnectionDefinition> list(String workspace) { return List.of(); }
            public boolean remove(String id, String workspace) { return false; }
        };
        var factory = new WorkspaceScopedJdbcConnectionFactory(registry,
                (workspace, subject, source, action) -> { throw new SecurityException("denied"); },
                d -> connection());
        assertThrows(SecurityException.class, () -> factory.open("sales", "workspace-a", "analyst"));
        assertFalse(lookedUp.get());
    }

    @Test void rejectsReadWriteDefinitions() {
        var factory = new WorkspaceScopedJdbcConnectionFactory(
                new ConnectionRegistry() {
                    public boolean register(ConnectionDefinition d) { return false; }
                    public Optional<ConnectionDefinition> find(String id, String workspace) {
                        return Optional.of(source(workspace, false));
                    }
                    public List<ConnectionDefinition> list(String workspace) { return List.of(); }
                    public boolean remove(String id, String workspace) { return false; }
                }, (w, s, id, action) -> {}, d -> connection());
        assertThrows(SecurityException.class, () -> factory.open("sales", "workspace-a", "analyst"));
    }
}
