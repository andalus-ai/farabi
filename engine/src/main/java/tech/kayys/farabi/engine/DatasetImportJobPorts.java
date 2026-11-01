package tech.kayys.farabi.engine;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Durable queue contract for retryable, workspace-scoped dataset imports. */
public final class DatasetImportJobPorts {
    private DatasetImportJobPorts() {}

    public enum Status { QUEUED, RUNNING, SUCCEEDED, FAILED }

    public record Job(
            UUID id, String workspaceId, UUID datasetId, String idempotencyKey,
            Status status, int attempts, int maxAttempts, String leaseOwner,
            UUID leaseToken, Instant leaseUntil, Instant availableAt, String errorCode, String errorMessage,
            Instant createdAt, Instant updatedAt) {
        public Job {
            Objects.requireNonNull(id, "id");
            QueryContracts.required(workspaceId, "workspaceId");
            Objects.requireNonNull(datasetId, "datasetId");
            QueryContracts.required(idempotencyKey, "idempotencyKey");
            Objects.requireNonNull(status, "status");
            if (attempts < 0 || maxAttempts < 1 || attempts > maxAttempts)
                throw new IllegalArgumentException("invalid attempt counters");
            Objects.requireNonNull(availableAt, "availableAt");\n            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
            if (status == Status.RUNNING && (leaseToken == null || leaseUntil == null))
                throw new IllegalArgumentException("running job requires a lease token and expiry");
        }
    }

    public record NewJob(String workspaceId, UUID datasetId, String idempotencyKey,
                         int maxAttempts, Instant now) {
        public NewJob {
            QueryContracts.required(workspaceId, "workspaceId");
            Objects.requireNonNull(datasetId, "datasetId");
            QueryContracts.required(idempotencyKey, "idempotencyKey");
            if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be positive");
            Objects.requireNonNull(now, "now");
        }
    }

    public record Lease(String workerId, Instant now, Instant leaseUntil) {
        public Lease {
            QueryContracts.required(workerId, "workerId");
            Objects.requireNonNull(now, "now");
            Objects.requireNonNull(leaseUntil, "leaseUntil");
            if (!leaseUntil.isAfter(now)) throw new IllegalArgumentException("leaseUntil must be after now");
        }
    }

    @FunctionalInterface
    public interface SourceResolver {
        DatasetImportPorts.StagedSource resolve(DatasetContracts.Dataset dataset);
    }

    public interface SubmissionStore {\n        /** Inserts dataset and job in one database transaction. */\n        Job registerAndEnqueue(DatasetContracts.Dataset dataset, NewJob request);\n    }\n\n    public interface Store {
        /** Idempotent within a workspace: repeated keys return the original job. */
        Job enqueue(NewJob request);
        Optional<Job> find(UUID jobId, String workspaceId);
        Optional<Job> findByIdempotencyKey(String workspaceId, String idempotencyKey);
        /** Claims one queued or expired job; null when no work is available. */
        Optional<Job> claim(Lease lease);
        boolean renew(UUID jobId, UUID leaseToken, Instant leaseUntil, Instant now);\n        boolean complete(UUID jobId, UUID leaseToken, Instant now);
        /** Requeues while attempts remain; otherwise moves the job to FAILED. */
        boolean fail(UUID jobId, UUID leaseToken, String errorCode, String message, Instant retryAt, Instant now);
    }
}
