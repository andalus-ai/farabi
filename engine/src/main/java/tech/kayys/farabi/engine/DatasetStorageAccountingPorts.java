package tech.kayys.farabi.engine;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Workspace quota and durable storage accounting contracts. */
public final class DatasetStorageAccountingPorts {
    private DatasetStorageAccountingPorts() {}

    public record Usage(String workspaceId, long quotaBytes, long usedBytes, long reservedBytes) {
        public Usage {
            QueryContracts.required(workspaceId, "workspaceId");
            if (quotaBytes < 0 || usedBytes < 0 || reservedBytes < 0 || usedBytes + reservedBytes > quotaBytes)
                throw new IllegalArgumentException("invalid workspace storage usage");
        }
        public long availableBytes() { return quotaBytes - usedBytes - reservedBytes; }
    }

    public record Reservation(UUID id, String workspaceId, long bytes, Instant createdAt) {
        public Reservation {
            if (id == null || bytes <= 0 || createdAt == null) throw new IllegalArgumentException("invalid storage reservation");
            QueryContracts.required(workspaceId, "workspaceId");
        }
    }

    public interface QuotaStore {
        Reservation reserve(String workspaceId, UUID reservationId, long bytes, Instant now);
        Optional<Usage> usage(String workspaceId);
        void releaseReservation(UUID reservationId, String workspaceId, Instant now);
    }

    /** Implemented by the same transactional store that registers dataset and import job. */
    public interface QuotaSubmissionStore {
        DatasetImportJobPorts.Job registerAndEnqueue(DatasetContracts.Dataset dataset,
                DatasetImportJobPorts.NewJob request, UUID reservationId, long actualBytes);
    }
}
