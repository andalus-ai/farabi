package tech.kayys.farabi.quarkus;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.DataSourceContracts.ProviderKind;

class JdbcConnectionPoolManagerTest {
    private static JdbcConnectionPoolManager.SourceDefinition source(
            String url, boolean readOnly, boolean tlsRequired) {
        return new JdbcConnectionPoolManager.SourceDefinition(
                "Warehouse", url, "farabi_reader", "vault://farabi/warehouse",
                readOnly, tlsRequired, 3, 0, 5_000);
    }

    @Test
    void createsRegistryEntryWithoutExposingResolvedSecretAndClosesPool() {
        AtomicInteger resolutions = new AtomicInteger();
        var manager = new JdbcConnectionPoolManager(
                Map.of("warehouse", source("jdbc:postgresql://db.example.test:5432/warehouse", true, true)),
                Set.of("db.example.test"),
                reference -> {
                    assertEquals("vault://farabi/warehouse", reference);
                    resolutions.incrementAndGet();
                    return "not-a-real-password";
                });
        try {
            assertEquals(1, manager.poolCount());
            assertEquals(1, resolutions.get());
            var descriptor = manager.registry().find("warehouse").orElseThrow();
            assertEquals(ProviderKind.JDBC, descriptor.providerKind());
            assertTrue(descriptor.readOnly());
            assertEquals("warehouse", manager.registry().list().getFirst().id());
        } finally {
            manager.close();
        }
    }

    @Test
    void rejectsHostsThatAreNotExplicitlyAllowlisted() {
        assertThrows(IllegalArgumentException.class, () -> new JdbcConnectionPoolManager(
                Map.of("warehouse", source("jdbc:postgresql://127.0.0.1:5432/warehouse", true, true)),
                Set.of("db.example.test"), reference -> "secret"));
    }

    @Test
    void rejectsNonTlsConnections() {
        assertThrows(IllegalArgumentException.class, () -> new JdbcConnectionPoolManager(
                Map.of("warehouse", source("jdbc:mysql://db.example.test:3306/warehouse", true, false)),
                Set.of("db.example.test"), reference -> "secret"));
    }

    @Test
    void rejectsWritableAnalyticalSources() {
        assertThrows(IllegalArgumentException.class, () -> new JdbcConnectionPoolManager(
                Map.of("warehouse", source("jdbc:postgresql://db.example.test:5432/warehouse", false, true)),
                Set.of("db.example.test"), reference -> "secret"));
    }

    @Test
    void rejectsTlsOverridesEmbeddedInJdbcUrl() {
        assertThrows(IllegalArgumentException.class, () -> new JdbcConnectionPoolManager(
                Map.of("warehouse", source("jdbc:postgresql://db.example.test:5432/warehouse?sslmode=disable", true, true)),
                Set.of("db.example.test"), reference -> "secret"));
    }

    @Test
    void rejectsAggregatePoolCapacityAboveRuntimeLimit() {
        Map<String, JdbcConnectionPoolManager.SourceDefinition> definitions = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 21; i++) {
            definitions.put("warehouse-" + i, new JdbcConnectionPoolManager.SourceDefinition(
                    "Warehouse " + i, "jdbc:postgresql://db.example.test:5432/warehouse",
                    "reader", "vault://warehouse/" + i, true, true, 10, 0, 5_000));
        }
        assertThrows(IllegalArgumentException.class, () -> new JdbcConnectionPoolManager(
                definitions, Set.of("db.example.test"), reference -> "secret"));
    }

    @Test
    void rejectsDriverSocketOverridesInJdbcUrl() {
        assertThrows(IllegalArgumentException.class, () -> new JdbcConnectionPoolManager(
                Map.of("warehouse", source("jdbc:postgresql://db.example.test:5432/warehouse?socketFactory=custom.Driver", true, true)),
                Set.of("db.example.test"), reference -> "secret"));
    }

    @Test
    void requiresSecretResolverWhenSourcesAreConfigured() {
        assertThrows(IllegalStateException.class, () -> new JdbcConnectionPoolManager(
                Map.of("warehouse", source("jdbc:postgresql://db.example.test:5432/warehouse", true, true)),
                Set.of("db.example.test"), null));
    }

    @Test
    void rejectsCredentialsEmbeddedInJdbcUrl() {
        assertThrows(IllegalArgumentException.class, () -> new JdbcConnectionPoolManager(
                Map.of("warehouse", source("jdbc:postgresql://user:pass@db.example.test:5432/warehouse", true, true)),
                Set.of("db.example.test"), reference -> "secret"));
    }
}
