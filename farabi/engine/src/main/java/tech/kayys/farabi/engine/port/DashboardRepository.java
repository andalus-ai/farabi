package tech.kayys.farabi.engine.port;

import tech.kayys.farabi.engine.domain.Dashboard;
import java.util.Optional;
import java.util.UUID;

public interface DashboardRepository {
    void save(Dashboard dashboard);
    Optional<Dashboard> findById(UUID id);
}
