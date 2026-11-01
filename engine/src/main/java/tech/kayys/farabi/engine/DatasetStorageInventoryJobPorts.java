package tech.kayys.farabi.engine;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Durable, workspace-scoped queue for read-only storage inventory inspections. */
public final class DatasetStorageInventoryJobPorts {
    private DatasetStorageInventoryJobPorts() {}
    public enum Status { QUEUED, RUNNING, SUCCEEDED, FAILED }
    public record Job(UUID id, String workspaceId, String idempotencyKey, Status status,
            int attempts, int maxAttempts, String leaseOwner, UUID leaseToken, Instant leaseUntil,
            Instant availableAt, UUID reportId, String errorCode, String errorMessage,
            Instant createdAt, Instant updatedAt) {
        public Job {
            Objects.requireNonNull(id); QueryContracts.required(workspaceId, "workspaceId");
            QueryContracts.required(idempotencyKey, "idempotencyKey"); Objects.requireNonNull(status);
            if (attempts < 0 || maxAttempts < 1 || attempts > maxAttempts) throw new IllegalArgumentException("invalid attempts");
            Objects.requireNonNull(availableAt); Objects.requireNonNull(createdAt); Objects.requireNonNull(updatedAt);
            if (status == Status.RUNNING && (leaseToken == null || leaseUntil == null))
                throw new IllegalArgumentException("running job requires lease");
        }
    }
    public record Lease(String workerId, Instant now, Instant until) {
        public Lease { QueryContracts.required(workerId, "workerId"); Objects.requireNonNull(now); Objects.requireNonNull(until);
            if (!until.isAfter(now)) throw new IllegalArgumentException("lease must be in the future"); }
    }
    public interface Store {
        Job enqueue(String workspaceId, String idempotencyKey, int maxAttempts, Instant now);
        Optional<Job> find(UUID id, String workspaceId);
        Optional<Job> claim(Lease lease);
        boolean complete(UUID id, UUID token, UUID reportId, Instant now);
        boolean fail(UUID id, UUID token, String code, String message, Instant retryAt, Instant now);
    }
}
