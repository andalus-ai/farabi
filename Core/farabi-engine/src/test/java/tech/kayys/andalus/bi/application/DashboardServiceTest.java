package tech.kayys.andalus.bi.application;

import org.junit.jupiter.api.Test;
import tech.kayys.andalus.bi.artifact.DashboardArtifact;
import tech.kayys.andalus.bi.artifact.WidgetSpec;
import tech.kayys.andalus.bi.persistence.DashboardConflictException;
import tech.kayys.andalus.bi.persistence.DashboardRepository;
import tech.kayys.andalus.bi.semantic.Aggregation;
import tech.kayys.andalus.bi.semantic.SemanticMetric;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DashboardServiceTest {
    private static final class FakeRepository implements DashboardRepository {
        private final Map<Integer, DashboardArtifact> versions = new HashMap<>();

        @Override public DashboardArtifact create(DashboardArtifact artifact) {
            if (!versions.isEmpty()) throw new DashboardConflictException("already exists");
            versions.put(1, artifact);
            return artifact;
        }

        @Override public DashboardArtifact append(DashboardArtifact artifact, int expectedPreviousVersion) {
            int latest = versions.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
            if (latest != expectedPreviousVersion) throw new DashboardConflictException("stale version");
            versions.put(artifact.version(), artifact);
            return artifact;
        }

        @Override public Optional<DashboardArtifact> latest(String workspaceId, UUID dashboardId) {
            return versions.values().stream()
                    .filter(a -> a.workspaceId().equals(workspaceId) && a.id().equals(dashboardId))
                    .max(java.util.Comparator.comparingInt(DashboardArtifact::version));
        }

        @Override public Optional<DashboardArtifact> version(String workspaceId, UUID dashboardId, int version) {
            return Optional.ofNullable(versions.get(version))
                    .filter(a -> a.workspaceId().equals(workspaceId) && a.id().equals(dashboardId));
        }

        @Override public List<Integer> versions(String workspaceId, UUID dashboardId) {
            return versions.values().stream()
                    .filter(a -> a.workspaceId().equals(workspaceId) && a.id().equals(dashboardId))
                    .map(DashboardArtifact::version).sorted().toList();
        }
    }

    @Test
    void createsAndAppendsWithOptimisticVersionCheck() {
        var repository = new FakeRepository();
        var service = new DashboardService(repository);
        var provenance = new DashboardArtifact.Provenance(
                DashboardArtifact.AuthoringMode.MANUAL, "user-1", "", "v1");
        var widget = new WidgetSpec("revenue", "Revenue", "number", "sales.total", null, null);
        var metrics = List.of(new SemanticMetric("sales.total", "Sales", "", "warehouse", "amount", Aggregation.SUM, "USD"));
        var created = service.create("ws-1", "Sales", "", List.of(widget), provenance, metrics);
        var updated = service.update("ws-1", created.id(), 1, "Sales 2", "", List.of(widget), provenance, metrics);

        assertEquals(2, updated.version());
        assertEquals(List.of(1, 2), service.versions("ws-1", created.id()));
        assertEquals("Sales 2", service.latest("ws-1", created.id()).orElseThrow().title());
        assertTrue(service.latest("ws-2", created.id()).isEmpty());
        assertThrows(DashboardConflictException.class, () ->
                service.update("ws-1", created.id(), 1, "stale", "", List.of(widget), provenance, metrics));
    }

    @Test
    void rejectsUnknownMetricBeforePersistence() {
        var repository = new FakeRepository();
        var service = new DashboardService(repository);
        var provenance = new DashboardArtifact.Provenance(
                DashboardArtifact.AuthoringMode.MANUAL, "user-1", "", "v1");
        var widget = new WidgetSpec("revenue", "Revenue", "number", "missing.metric", null, null);
        assertThrows(IllegalArgumentException.class, () ->
                service.create("ws", "Invalid", "", List.of(widget), provenance, List.of()));
        assertTrue(repository.versions.isEmpty());
    }
}
