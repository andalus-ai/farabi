package tech.kayys.farabi.engine;

import java.util.Optional;
import java.util.UUID;
import tech.kayys.farabi.engine.DatasetContracts.Dataset;
import tech.kayys.farabi.engine.DatasetContracts.Profile;

/** Hexagonal ports for workspace-scoped dataset catalog operations. */
public final class DatasetPorts {
    private DatasetPorts() {}

    @FunctionalInterface
    public interface Authorizer {
        void authorizeRegistration(String workspaceId, String subjectId, String sourceId);
    }

    public interface Catalog {
        /** Atomically inserts a dataset; returns false when the workspace/name already exists. */
        boolean create(Dataset dataset);
        Optional<Dataset> find(UUID datasetId, String workspaceId);
        Optional<Profile> findProfile(UUID datasetId, String workspaceId);
        void saveProfile(Profile profile);
    }
}
