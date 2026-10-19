package tech.kayys.farabi.runtime;

import tech.kayys.farabi.engine.application.DashboardService;
import tech.kayys.farabi.engine.port.DashboardRepository;
import java.util.Objects;

public final class FarabiRuntime {
    private final DashboardService dashboards;
    private FarabiRuntime(DashboardRepository repository) {
        this.dashboards = new DashboardService(Objects.requireNonNull(repository));
    }
    public static FarabiRuntime create(DashboardRepository repository) {
        return new FarabiRuntime(repository);
    }
    public DashboardService dashboards() { return dashboards; }
}
