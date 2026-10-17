package tech.kayys.andalus.bi.application;

import tech.kayys.andalus.bi.artifact.DashboardArtifact;
import tech.kayys.andalus.bi.artifact.WidgetSpec;
import tech.kayys.andalus.bi.security.AccessContext;
import tech.kayys.andalus.bi.semantic.SemanticMetric;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Authorization boundary for dashboard application operations.
 * Callers must pass an AccessContext created by a trusted authenticator.
 */
public final class AuthorizedDashboardService {
    private final DashboardService service;

    public AuthorizedDashboardService(DashboardService service) {
        this.service = Objects.requireNonNull(service);
    }

    public DashboardArtifact create(AccessContext actor, String workspaceId, String title, String description,
                                    List<WidgetSpec> widgets, Collection<SemanticMetric> allowedMetrics) {
        requireActor(actor, workspaceId);
        return service.create(workspaceId, title, description, widgets,
                provenance(actor), allowedMetrics);
    }

    public DashboardArtifact update(AccessContext actor, String workspaceId, UUID dashboardId,
                                    int expectedVersion, String title, String description,
                                    List<WidgetSpec> widgets, Collection<SemanticMetric> allowedMetrics) {
        requireActor(actor, workspaceId);
        return service.update(workspaceId, dashboardId, expectedVersion, title, description,
                widgets, provenance(actor), allowedMetrics);
    }

    public Optional<DashboardArtifact> latest(AccessContext actor, String workspaceId, UUID dashboardId) {
        requireActor(actor, workspaceId);
        return service.latest(workspaceId, dashboardId);
    }

    public List<Integer> versions(AccessContext actor, String workspaceId, UUID dashboardId) {
        requireActor(actor, workspaceId);
        return service.versions(workspaceId, dashboardId);
    }

    private static DashboardArtifact.Provenance provenance(AccessContext actor) {
        return new DashboardArtifact.Provenance(
                DashboardArtifact.AuthoringMode.MANUAL, actor.subjectId(), "", "");
    }

    private static void requireActor(AccessContext actor, String workspaceId) {
        Objects.requireNonNull(actor, "authenticated actor required").requireWorkspace(workspaceId);
    }
}
