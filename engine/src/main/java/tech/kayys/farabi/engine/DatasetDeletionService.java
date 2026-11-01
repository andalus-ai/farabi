package tech.kayys.farabi.engine;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import tech.kayys.farabi.engine.DatasetStorageLifecyclePorts.*;

/** Authorization-first deletion request; physical deletion is asynchronous and retryable. */
public final class DatasetDeletionService {
    private final Authorizer authorizer;
    private final Store store;
    private final Clock clock;
    private final int maxAttempts;

    public DatasetDeletionService(Authorizer authorizer, Store store, Clock clock, int maxAttempts) {
        this.authorizer = Objects.requireNonNull(authorizer);
        this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock);
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be positive");
        this.maxAttempts = maxAttempts;
    }

    public Optional<Deletion> request(UUID datasetId, String workspaceId, String subjectId) {
        Objects.requireNonNull(datasetId);
        if (workspaceId == null || workspaceId.isBlank() || subjectId == null || subjectId.isBlank())
            throw new IllegalArgumentException("workspaceId and subjectId required");
        authorizer.authorizeDeletion(workspaceId, subjectId, datasetId);
        return store.request(datasetId, workspaceId, clock.instant(), maxAttempts);
    }
}
