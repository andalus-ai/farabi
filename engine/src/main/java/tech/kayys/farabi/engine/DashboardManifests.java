package tech.kayys.farabi.engine;

import java.time.Instant;
import java.util.*;
import tech.kayys.farabi.engine.VisualizationContracts.DashboardSpec;

/** Immutable versioned visualization snapshot independent of a particular UI renderer. */
public final class DashboardManifests {
    private DashboardManifests(){}
    public static final int SCHEMA_VERSION=1;
    public record Manifest(UUID id,UUID draftId,String workspaceId,int revision,int schemaVersion,
                           DashboardSpec spec,String promptProvenance,String publishedBy,Instant publishedAt) {
        public Manifest {
            Objects.requireNonNull(id);Objects.requireNonNull(draftId);
            QueryContracts.required(workspaceId,"workspaceId");
            if(revision<1||schemaVersion!=SCHEMA_VERSION)throw new IllegalArgumentException("unsupported manifest version");
            Objects.requireNonNull(spec);QueryContracts.required(publishedBy,"publishedBy");
            Objects.requireNonNull(publishedAt);
        }
    }
    public interface Repository {
        void save(Manifest manifest);
        Optional<Manifest> find(UUID id,String workspaceId);
    }
}
