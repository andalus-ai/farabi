package tech.kayys.farabi.engine.application;

import tech.kayys.farabi.engine.domain.Dashboard;
import tech.kayys.farabi.engine.port.DashboardRepository;
import java.util.Objects;
import java.util.UUID;

public final class DashboardService {
    private final DashboardRepository repository;
    public DashboardService(DashboardRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }
    public Dashboard create(String workspaceId, String title) {
        Dashboard dashboard = new Dashboard(UUID.randomUUID(), workspaceId, title);
        repository.save(dashboard);
        return dashboard;
    }
    public Dashboard get(UUID id, String workspaceId) {
        Objects.requireNonNull(id);
        if (workspaceId == null || workspaceId.isBlank()) throw new IllegalArgumentException("workspaceId required");
        Dashboard dashboard = repository.findById(id).orElseThrow(() -> new IllegalArgumentException("dashboard not found"));
        if (!dashboard.workspaceId().equals(workspaceId)) throw new IllegalArgumentException("dashboard not found");
        return dashboard;
    }
}
