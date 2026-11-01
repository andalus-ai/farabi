package tech.kayys.farabi.engine;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.DatasetContracts.*;

class DatasetCatalogServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");

    @Test
    void authorizesBeforePersistingAndRegistersWorkspaceScopedDataset() {
        Map<UUID, Dataset> stored = new HashMap<>();
        var catalog = catalog(stored);
        var service = new DatasetCatalogService(
                (workspace, subject, source) -> {
                    assertEquals("workspace-a", workspace);
                    assertEquals("analyst", subject);
                    assertEquals("warehouse", source);
                }, catalog, Clock.fixed(NOW, ZoneOffset.UTC));

        Dataset dataset = service.register(new DatasetCatalogService.RegisterRequest(
                "workspace-a", "analyst", "Orders", "warehouse", Format.PARQUET, "catalog.orders"));

        assertEquals(Lifecycle.REGISTERED, dataset.lifecycle());
        assertEquals(NOW, dataset.createdAt());
        assertSame(dataset, stored.get(dataset.id()));
        assertTrue(catalog.find(dataset.id(), "workspace-b").isEmpty());
        assertEquals(dataset, catalog.find(dataset.id(), "workspace-a").orElseThrow());
    }

    @Test
    void deniedRegistrationDoesNotWriteToCatalog() {
        Map<UUID, Dataset> stored = new HashMap<>();
        var service = new DatasetCatalogService(
                (workspace, subject, source) -> { throw new SecurityException("source access denied"); },
                catalog(stored), Clock.fixed(NOW, ZoneOffset.UTC));

        assertThrows(SecurityException.class, () -> service.register(new DatasetCatalogService.RegisterRequest(
                "workspace-a", "analyst", "Orders", "warehouse", Format.CSV, "upload:123")));
        assertTrue(stored.isEmpty());
    }

    @Test
    void rejectsDuplicateWorkspaceName() {
        Map<UUID, Dataset> stored = new HashMap<>();
        var service = new DatasetCatalogService((w, s, source) -> {}, catalog(stored), Clock.fixed(NOW, ZoneOffset.UTC));
        var request = new DatasetCatalogService.RegisterRequest(
                "workspace-a", "analyst", "Orders", "warehouse", Format.CSV, "upload:123");
        service.register(request);
        assertThrows(IllegalStateException.class, () -> service.register(request));
        assertEquals(1, stored.size());
    }

    @Test
    void profileContractsAreImmutableAndValidateCounts() {
        var profile = new Profile(UUID.randomUUID(), "workspace-a", 10,
                java.util.List.of(new Column("amount", LogicalType.DECIMAL, true, 1, 8)), NOW, "profiler-1");
        assertThrows(UnsupportedOperationException.class,
                () -> profile.columns().add(new Column("id", LogicalType.INTEGER, false, 0, 10)));
        assertThrows(IllegalArgumentException.class,
                () -> new Profile(UUID.randomUUID(), "workspace-a", -1, java.util.List.of(), NOW, "v1"));
        assertThrows(IllegalArgumentException.class,
                () -> new Column("amount", LogicalType.DECIMAL, true, -1, 0));
    }

    private static DatasetPorts.Catalog catalog(Map<UUID, Dataset> stored) {
        return new DatasetPorts.Catalog() {
            @Override public boolean create(Dataset dataset) {
                boolean duplicate = stored.values().stream().anyMatch(existing ->
                        existing.workspaceId().equals(dataset.workspaceId())
                                && existing.name().equalsIgnoreCase(dataset.name()));
                if (duplicate) return false;
                stored.put(dataset.id(), dataset);
                return true;
            }
            @Override public Optional<Dataset> find(UUID id, String workspace) {
                Dataset dataset = stored.get(id);
                return dataset != null && dataset.workspaceId().equals(workspace)
                        ? Optional.of(dataset) : Optional.empty();
            }
            @Override public Optional<Profile> findProfile(UUID id, String workspace) { return Optional.empty(); }
            @Override public void saveProfile(Profile profile) {}
        };
    }
}
