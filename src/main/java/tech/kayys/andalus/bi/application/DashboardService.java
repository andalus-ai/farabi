package tech.kayys.andalus.bi.application;

import tech.kayys.andalus.bi.artifact.DashboardArtifact;
import tech.kayys.andalus.bi.artifact.DashboardValidator;
import tech.kayys.andalus.bi.artifact.WidgetSpec;
import tech.kayys.andalus.bi.persistence.DashboardRepository;
import tech.kayys.andalus.bi.semantic.SemanticMetric;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain orchestration. Authentication/authorization must be enforced by a
 * trusted API boundary before calling this service; client-provided scope
 * must never be trusted without verification.
 */
public final class DashboardService {
    private final DashboardRepository repository;

    public DashboardService(DashboardRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    public DashboardArtifact create(String workspaceId, String title, String description,
                                    List<WidgetSpec> widgets, DashboardArtifact.Provenance provenance,
                                    Collection<SemanticMetric> allowedMetrics) {
        var artifact = DashboardArtifact.create(workspaceId, title, description, widgets, provenance);
        DashboardValidator.requireValid(artifact, allowedMetrics);
        return repository.create(artifact);
    }

    public DashboardArtifact update(String workspaceId, UUID dashboardId, int expectedVersion,
                                    String title, String description, List<WidgetSpec> widgets,
                                    DashboardArtifact.Provenance provenance,
                                    Collection<SemanticMetric> allowedMetrics) {
        if (expectedVersion < 1) throw new IllegalArgumentException("expectedVersion must be >= 1");
        var current = repository.version(workspaceId, dashboardId, expectedVersion)
                .orElseThrow(() -> new IllegalArgumentException("dashboard version not found"));
        var next = current.nextVersion(title, description, widgets, provenance);
        DashboardValidator.requireValid(next, allowedMetrics);
        return repository.append(next, expectedVersion);
    }

    public Optional<DashboardArtifact> latest(String workspaceId, UUID dashboardId) {
        return repository.latest(workspaceId, dashboardId);
    }

    public List<Integer> versions(String workspaceId, UUID dashboardId) {
        return repository.versions(workspaceId, dashboardId);
    }
}
