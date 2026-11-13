package tech.kayys.farabi.engine;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.DataSourceContracts.*;

class DataSourceContractsTest {
    @Test
    void connectionDefinitionKeepsCredentialsAsReferenceAndCopiesProperties() {
        var properties = new java.util.HashMap<String, String>();
        properties.put("sslmode", "verify-full");
        var definition = new ConnectionDefinition(
                "sales", "workspace-a", "Sales", ProviderKind.JDBC, "jdbc-postgresql",
                URI.create("jdbc:postgresql://db.internal:5432/sales"), "sales", "public",
                properties, new SecretReference("secret://sales-db"), true, Instant.EPOCH);
        properties.put("sslmode", "disable");
        assertEquals("verify-full", definition.properties().get("sslmode"));
        assertEquals("secret://sales-db", definition.credential().reference());
        assertThrows(UnsupportedOperationException.class,
                () -> definition.properties().put("x", "y"));
    }

    @Test
    void rejectsCredentialsEmbeddedInEndpoint() {
        assertThrows(IllegalArgumentException.class, () -> new ConnectionDefinition(
                "sales", "workspace-a", "Sales", ProviderKind.JDBC, "jdbc-postgresql",
                URI.create("https://user:password@db.internal"), null, null, Map.of(),
                null, true, Instant.EPOCH));
    }

    @Test
    void rejectsEndpointQueryAndFragment() {
        assertThrows(IllegalArgumentException.class, () -> new ConnectionDefinition(
                "sales", "workspace-a", "Sales", ProviderKind.JDBC, "jdbc-postgresql",
                URI.create("https://db.internal/path?token=secret"), null, null, Map.of(),
                null, true, Instant.EPOCH));
    }
}
