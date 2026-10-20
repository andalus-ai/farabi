package tech.kayys.farabi.engine;

import java.time.Instant;
import java.util.*;

/** Framework-neutral, immutable dashboard draft and published revision contracts. */
public final class DashboardArtifacts {
    private DashboardArtifacts() {}
    public record Chart(String id,String kind,String datasetId,String measureId) {
        public Chart {
            QueryContracts.required(id,"chart id"); QueryContracts.required(kind,"chart kind");
            QueryContracts.required(datasetId,"datasetId"); QueryContracts.required(measureId,"measureId");
        }
    }
    public record Draft(UUID id,String workspaceId,String ownerId,String title,List<Chart> charts,String promptProvenance) {
        public Draft {
            Objects.requireNonNull(id); QueryContracts.required(workspaceId,"workspaceId");
            QueryContracts.required(ownerId,"ownerId"); QueryContracts.required(title,"title");
            charts=List.copyOf(Objects.requireNonNull(charts));
            if(charts.isEmpty()) throw new IllegalArgumentException("dashboard requires charts");
        }
    }
    public record Published(UUID id,UUID draftId,String workspaceId,String title,int revision,
                            List<Chart> charts,String promptProvenance,String publishedBy,Instant publishedAt) {
        public Published {
            Objects.requireNonNull(id);Objects.requireNonNull(draftId);
            QueryContracts.required(workspaceId,"workspaceId");QueryContracts.required(title,"title");
            QueryContracts.required(publishedBy,"publishedBy");Objects.requireNonNull(publishedAt);
            if(revision<1) throw new IllegalArgumentException("revision must be positive");
            charts=List.copyOf(Objects.requireNonNull(charts));
        }
    }
    public interface Repository {
        void save(Published artifact);
        Optional<Published> find(UUID artifactId);
    }
}
