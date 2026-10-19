package tech.kayys.farabi.engine.application;

import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.domain.Dashboard;
import tech.kayys.farabi.engine.port.DashboardRepository;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class DashboardServiceTest {
    @Test void persistsAndScopesDashboards() {
        var store = new HashMap<UUID, Dashboard>();
        DashboardRepository repository = new DashboardRepository() {
            public void save(Dashboard d) { store.put(d.id(), d); }
            public Optional<Dashboard> findById(UUID id) { return Optional.ofNullable(store.get(id)); }
        };
        var service = new DashboardService(repository);
        var dashboard = service.create("workspace-a", "Sales");
        assertEquals(dashboard, service.get(dashboard.id(), "workspace-a"));
        assertThrows(IllegalArgumentException.class, () -> service.get(dashboard.id(), "workspace-b"));
    }
}
