package tech.kayys.farabi.engine;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.DatasetContracts.*;
import tech.kayys.farabi.engine.DatasetImportPorts.*;

class DatasetImportServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");

    @Test
    void authorizesBeforeStagingThenRegistersAndPersistsProfile() {
        Map<UUID, Dataset> datasets = new HashMap<>();
        Map<UUID, Profile> profiles = new HashMap<>();
        DatasetPorts.Catalog catalog = catalog(datasets, profiles);
        var service = new DatasetImportService(
                (workspace, subject, source) -> assertEquals("workspace-a", workspace),
                catalog,
                request -> new StagedSource(Format.CSV, "object://workspace-a/upload-1"),
                (id, workspace, staged) -> new Profile(id, workspace, 42,
                        List.of(new Column("amount", LogicalType.DECIMAL, false, 0, 17)), NOW, "profile-1"),
                Clock.fixed(NOW, ZoneOffset.UTC));

        var result = service.importAndProfile(request());

        assertEquals(Lifecycle.REGISTERED, result.dataset().lifecycle());
        assertEquals(42, result.profile().rowCount());
        assertEquals(result.profile(), profiles.get(result.dataset().id()));
    }

    @Test
    void deniedImportNeverStagesOrWrites() {
        Map<UUID, Dataset> datasets = new HashMap<>();
        DatasetPorts.Catalog catalog = catalog(datasets, new HashMap<>());
        var service = new DatasetImportService(
                (workspace, subject, source) -> { throw new SecurityException("denied"); },
                catalog,
                request -> { fail("intake must not run before authorization"); return null; },
                (id, workspace, staged) -> { fail("profiler must not run"); return null; },
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThrows(SecurityException.class, () -> service.importAndProfile(request()));
        assertTrue(datasets.isEmpty());
    }

    @Test
    void rejectsProfilerResultForAnotherWorkspace() {
        Map<UUID, Dataset> datasets = new HashMap<>();
        var service = new DatasetImportService(
                (workspace, subject, source) -> {}, catalog(datasets, new HashMap<>()),
                request -> new StagedSource(Format.CSV, "object://upload"),
                (id, workspace, staged) -> new Profile(id, "workspace-other", 1, List.of(), NOW, "v1"),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThrows(IllegalArgumentException.class, () -> service.importAndProfile(request()));
        assertEquals(1, datasets.size());
    }

    private static ImportRequest request() {
        return new ImportRequest("workspace-a", "analyst", "Orders", "upload", Format.CSV,
                "orders.csv", 1024, "text/csv");
    }

    private static DatasetPorts.Catalog catalog(Map<UUID, Dataset> datasets, Map<UUID, Profile> profiles) {
        return new DatasetPorts.Catalog() {
            @Override public boolean create(Dataset dataset) {
                if (datasets.values().stream().anyMatch(d -> d.workspaceId().equals(dataset.workspaceId())
                        && d.name().equalsIgnoreCase(dataset.name()))) return false;
                datasets.put(dataset.id(), dataset);
                return true;
            }
            @Override public Optional<Dataset> find(UUID id, String workspace) {
                Dataset d = datasets.get(id);
                return d != null && d.workspaceId().equals(workspace) ? Optional.of(d) : Optional.empty();
            }
            @Override public Optional<Profile> findProfile(UUID id, String workspace) {
                Profile p = profiles.get(id);
                return p != null && p.workspaceId().equals(workspace) ? Optional.of(p) : Optional.empty();
            }
            @Override public void saveProfile(Profile profile) { profiles.put(profile.datasetId(), profile); }
        };
    }
}
