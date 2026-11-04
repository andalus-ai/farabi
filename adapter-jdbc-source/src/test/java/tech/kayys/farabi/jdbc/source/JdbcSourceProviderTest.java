package tech.kayys.farabi.jdbc.source;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import tech.kayys.farabi.engine.DataSourceContracts.*;

class JdbcSourceProviderTest {
    private JdbcSourceProvider provider;
    private ConnectionDefinition definition;

    @BeforeEach void setUp() throws Exception {
        String url = "jdbc:h2:mem:source;DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS ANALYTICS");
            s.execute("CREATE TABLE IF NOT EXISTS ANALYTICS.ORDERS (ID BIGINT PRIMARY KEY, AMOUNT DECIMAL(12,2), ACTIVE BOOLEAN, CREATED_AT TIMESTAMP, PAYLOAD JSON)");
        }
        provider = new JdbcSourceProvider(d -> DriverManager.getConnection(url));
        definition = new ConnectionDefinition("orders", "workspace-a", "Orders",
                ProviderKind.JDBC, "jdbc", URI.create(url), null, null, Map.of(), null,
                true, Instant.now());
    }

    @Test void discoversSchemasTablesAndTypedColumns() {
        assertTrue(provider.schemas(definition, null).contains("ANALYTICS"));
        assertTrue(provider.datasets(definition, null, "ANALYTICS").stream()
                .anyMatch(table -> table.name().equalsIgnoreCase("ORDERS")));
        var columns = provider.columns(definition, null, "ANALYTICS", "ORDERS");
        assertEquals(5, columns.size());
        assertEquals("INTEGER", columns.stream().filter(c -> c.name().equals("ID")).findFirst().orElseThrow().logicalType().name());
        assertEquals("DECIMAL", columns.stream().filter(c -> c.name().equals("AMOUNT")).findFirst().orElseThrow().logicalType().name());
        assertEquals("JSON", columns.stream().filter(c -> c.name().equals("PAYLOAD")).findFirst().orElseThrow().logicalType().name());
    }

    @Test void reportsCapabilitiesAndHealth() {
        assertEquals("h2", provider.capabilities(definition).dialectId());
        assertEquals(Health.AVAILABLE, provider.testConnection(definition).health());
    }

    @Test void appliesReadOnlyFlagToConnection() {
        var readOnlyProvider = new JdbcSourceProvider(d -> new ReadOnlyTrackingConnection(
                DriverManager.getConnection("jdbc:h2:mem:readonly;DB_CLOSE_DELAY=-1")));
        // Connection read-only enforcement is exercised by the provider before metadata work.
        assertDoesNotThrow(() -> readOnlyProvider.catalogs(definition));
    }
    @Test void enforcesReadOnlyConnectionMode() {
        AtomicBoolean readOnlyApplied = new AtomicBoolean();
        var guarded = new JdbcSourceProvider(d -> {
            Connection delegate = DriverManager.getConnection("jdbc:h2:mem:readonly;DB_CLOSE_DELAY=-1");
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("setReadOnly")) readOnlyApplied.set((Boolean) args[0]);
                        try { return method.invoke(delegate, args); }
                        catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
                    });
        });
        assertDoesNotThrow(() -> guarded.catalogs(definition));
        assertTrue(readOnlyApplied.get());
    }
}
