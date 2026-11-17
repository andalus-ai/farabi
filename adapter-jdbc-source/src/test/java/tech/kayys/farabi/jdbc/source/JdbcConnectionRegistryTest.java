package tech.kayys.farabi.jdbc.source;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.DataSourceContracts.ConnectionDefinition;
import tech.kayys.farabi.engine.DataSourceContracts.ProviderKind;
import tech.kayys.farabi.engine.DataSourceContracts.SecretReference;

class JdbcConnectionRegistryTest {
    private JdbcConnectionRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:registry-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE farabi_data_sources (
                        source_id VARCHAR(63) NOT NULL,
                        workspace_id VARCHAR(128) NOT NULL,
                        display_name VARCHAR(255) NOT NULL,
                        provider_kind VARCHAR(32) NOT NULL,
                        provider_key VARCHAR(128) NOT NULL,
                        endpoint TEXT NOT NULL,
                        catalog_name VARCHAR(255),
                        schema_name VARCHAR(255),
                        credential_ref VARCHAR(1024),
                        read_only BOOLEAN NOT NULL,
                        created_at TIMESTAMP NOT NULL,
                        PRIMARY KEY (workspace_id, source_id))
                    """);
            statement.execute("""
                    CREATE TABLE farabi_data_source_properties (
                        source_id VARCHAR(63) NOT NULL,
                        workspace_id VARCHAR(128) NOT NULL,
                        property_key VARCHAR(255) NOT NULL,
                        property_value TEXT NOT NULL,
                        PRIMARY KEY (workspace_id, source_id, property_key),
                        FOREIGN KEY (workspace_id, source_id)
                            REFERENCES farabi_data_sources (workspace_id, source_id) ON DELETE CASCADE)
                    """);
        }
        registry = new JdbcConnectionRegistry(source);
    }

    @Test
    void persistsAndRestoresDefinitionAndProperties() {
        ConnectionDefinition expected = definition("workspace-a", "warehouse-a");

        assertTrue(registry.register(expected));
        assertEquals(Optional.of(expected), registry.find("warehouse-a", "workspace-a"));
        assertEquals(List.of(expected), registry.list("workspace-a"));
    }

    @Test
    void scopesIdenticalSourceIdsByWorkspace() {
        assertTrue(registry.register(definition("workspace-a", "warehouse")));
        assertTrue(registry.register(definition("workspace-b", "warehouse")));

        assertEquals("workspace-a", registry.find("warehouse", "workspace-a").orElseThrow().workspaceId());
        assertEquals("workspace-b", registry.find("warehouse", "workspace-b").orElseThrow().workspaceId());
        assertEquals(1, registry.list("workspace-a").size());
        assertEquals(1, registry.list("workspace-b").size());
    }

    @Test
    void rejectsDuplicateSourceWithinSameWorkspace() {
        assertTrue(registry.register(definition("workspace-a", "warehouse")));
        assertFalse(registry.register(definition("workspace-a", "warehouse")));
    }

    @Test
    void removesDefinitionAndItsProperties() throws Exception {
        assertTrue(registry.register(definition("workspace-a", "warehouse")));

        assertTrue(registry.remove("warehouse", "workspace-a"));
        assertTrue(registry.find("warehouse", "workspace-a").isEmpty());
        assertFalse(registry.remove("warehouse", "workspace-a"));
    }

    private static ConnectionDefinition definition(String workspace, String source) {
        return new ConnectionDefinition(source, workspace, "Warehouse", ProviderKind.JDBC, "jdbc",
                URI.create("jdbc:postgresql://db.example.test:5432/warehouse"),
                "warehouse", "public", Map.of("username", "reader", "applicationName", "farabi"),
                new SecretReference("vault://workspace/warehouse"), true,
                Instant.parse("2026-11-14T00:00:00Z"));
    }
}
