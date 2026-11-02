package tech.kayys.farabi.engine;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import tech.kayys.farabi.engine.DatasetContracts.Dataset;

/** Ports for durable, workspace-scoped dataset object deletion. */
public final class DatasetStorageLifecyclePorts {
    private DatasetStorageLifecyclePorts() {}

    public record Deletion(UUID id, UUID datasetId, String workspaceId, String locationRef,
            String status, int attempts, int maxAttempts, UUID leaseToken,
            Instant leaseUntil, Instant availableAt) {
        public Deletion {
            if (id == null || datasetId == null || leaseToken == null && "RUNNING".equals(status)
                    || workspaceId == null || workspaceId.isBlank()
                    || locationRef == null || locationRef.isBlank()
                    || status == null || attempts < 0 || maxAttempts < 1
                    || availableAt == null) throw new IllegalArgumentException("invalid deletion job");
        }
    }

    public record Lease(String workerId, Instant now, Instant until) {
        public Lease {
            if (workerId == null || workerId.isBlank() || now == null || until == null || !until.isAfter(now))
                throw new IllegalArgumentException("invalid deletion lease");
        }
    }

    public interface Store {
        /** Legacy direct request port; implementations must fail closed when maker-checker governance is enabled. */
        Optional<Deletion> request(UUID datasetId, String workspaceId, Instant now, int maxAttempts);
        Optional<Deletion> claim(Lease lease);
        boolean complete(UUID jobId, UUID token, Instant now);
        boolean fail(UUID jobId, UUID token, Instant retryAt, Instant now);
    }

    @FunctionalInterface
    public interface Authorizer {
        void authorizeDeletion(String workspaceId, String subjectId, UUID datasetId);
    }

    @FunctionalInterface
    public interface ObjectStore {
        /** Must validate that the opaque location reference belongs to workspaceId. */
        void delete(String locationRef, String workspaceId);
    }
}