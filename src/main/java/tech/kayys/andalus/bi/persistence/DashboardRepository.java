package tech.kayys.andalus.bi.persistence;

import tech.kayys.andalus.bi.artifact.DashboardArtifact;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DashboardRepository {
    DashboardArtifact create(DashboardArtifact artifact);
    DashboardArtifact append(DashboardArtifact artifact, int expectedPreviousVersion);
    Optional<DashboardArtifact> latest(String workspaceId, UUID dashboardId);
    Optional<DashboardArtifact> version(String workspaceId, UUID dashboardId, int version);
    List<Integer> versions(String workspaceId, UUID dashboardId);
}
