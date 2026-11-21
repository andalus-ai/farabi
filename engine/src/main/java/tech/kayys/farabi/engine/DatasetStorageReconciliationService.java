package tech.kayys.farabi.engine;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import tech.kayys.farabi.engine.DatasetStorageReconciliationPorts.*;
/** Explicit workspace-scoped, read-only reconciliation application service. */
public final class DatasetStorageReconciliationService {
 private final Store store; private final Clock clock;
 public DatasetStorageReconciliationService(Store store,Clock clock){this.store=Objects.requireNonNull(store);this.clock=Objects.requireNonNull(clock);}
 public Report inspect(String workspaceId){required(workspaceId);return store.inspect(workspaceId,clock.instant());}
 public List<Report> recent(String workspaceId,int limit){required(workspaceId);if(limit<1||limit>100)throw new IllegalArgumentException("limit must be between 1 and 100");return store.recent(workspaceId,limit);}
 private static void required(String s){if(s==null||s.isBlank())throw new IllegalArgumentException("workspaceId required");}
}
