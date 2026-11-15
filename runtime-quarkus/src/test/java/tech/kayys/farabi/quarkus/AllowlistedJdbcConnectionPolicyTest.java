package tech.kayys.farabi.quarkus;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.time.Instant;
import java.util.Properties;
import tech.kayys.farabi.engine.DataSourceContracts.ConnectionDefinition;
import tech.kayys.farabi.engine.DataSourceContracts.ProviderKind;
import org.junit.jupiter.api.Test;

class AllowlistedJdbcConnectionPolicyTest {
    private static ConnectionDefinition definition(String endpoint, boolean readOnly) {
        return new ConnectionDefinition("warehouse", "workspace-a", "Warehouse", ProviderKind.JDBC,
                "jdbc", URI.create(endpoint), null, null, java.util.Map.of("username", "reader"),
                null, readOnly, Instant.parse("2026-11-14T00:00:00Z"));
    }

    @Test
    void allowsOnlyConfiguredHostAndForcesVerifiedTls() {
        var policy = new AllowlistedJdbcConnectionPolicy("db.example.test");
        Properties postgres = new Properties();
        postgres.setProperty("user", "reader");
        policy.validateAndApply(definition("jdbc:postgresql://db.example.test:5432/warehouse", true), postgres);
        assertEquals("verify-full", postgres.getProperty("sslmode"));

        Properties mysql = new Properties();
        mysql.setProperty("user", "reader");
        policy.validateAndApply(definition("jdbc:mysql://db.example.test:3306/warehouse", true), mysql);
        assertEquals("VERIFY_IDENTITY", mysql.getProperty("sslMode"));
    }

    @Test
    void rejectsUnlistedHostsAndWritableSources() {
        var policy = new AllowlistedJdbcConnectionPolicy("db.example.test");
        assertThrows(SecurityException.class, () -> policy.validateAndApply(
                definition("jdbc:postgresql://other.example.test:5432/warehouse", true), new Properties()));
        assertThrows(SecurityException.class, () -> policy.validateAndApply(
                definition("jdbc:postgresql://db.example.test:5432/warehouse", false), new Properties()));
    }

    @Test
    void rejectsQueryOverridesAndUnsupportedProperties() {
        var policy = new AllowlistedJdbcConnectionPolicy("db.example.test");
        assertThrows(SecurityException.class, () -> policy.validateAndApply(
                definition("jdbc:postgresql://db.example.test:5432/warehouse?sslmode=disable", true),
                new Properties()));
        Properties properties = new Properties();
        properties.setProperty("socketFactory", "attacker.Driver");
        assertThrows(IllegalArgumentException.class, () -> policy.validateAndApply(
                definition("jdbc:postgresql://db.example.test:5432/warehouse", true), properties));
    }
}
