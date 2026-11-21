package tech.kayys.farabi.engine;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Explicit approval boundary for applying a previously reviewed quota repair proposal. */
public final class DatasetStorageQuotaRepairApplicationService {
    public record Application(UUID id, UUID previewId, String workspaceId, String approvedBy, Instant appliedAt,
            long previousUsedBytes, long appliedUsedBytes, long previousReservedBytes, long appliedReservedBytes) {
        public Application {
            Objects.requireNonNull(id); Objects.requireNonNull(previewId); Objects.requireNonNull(appliedAt);
            if (workspaceId == null || workspaceId.isBlank() || approvedBy == null || approvedBy.isBlank()
                    || previousUsedBytes < 0 || appliedUsedBytes < 0 || previousReservedBytes < 0
                    || appliedReservedBytes < 0) throw new IllegalArgumentException("invalid quota repair application");
        }
    }
    public interface Applications {
        /** Must validate the snapshot and atomically update counters plus append the application audit event. */
        Application apply(DatasetStorageQuotaRepairPreviewService.Plan preview, String approvedBy, Instant now);
        Optional<Application> findByPreview(UUID previewId, String workspaceId);
    }
    public static final class StalePreviewException extends RuntimeException {
        public StalePreviewException(String message) { super(message); }
    }
    public static final class InapplicablePreviewException extends RuntimeException {
        public InapplicablePreviewException(String message) { super(message); }
    }

    private final DatasetStorageQuotaRepairPreviewService previews;
    private final Applications applications;
    private final Clock clock;

    public DatasetStorageQuotaRepairApplicationService(DatasetStorageQuotaRepairPreviewService previews,
            Applications applications, Clock clock) {
        this.previews = Objects.requireNonNull(previews);
        this.applications = Objects.requireNonNull(applications);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Calling this operation is the explicit operator approval; the application is idempotent per preview. */
    public Application approveAndApply(UUID previewId, String workspaceId, String approverId) {
        Objects.requireNonNull(previewId);
        required(workspaceId, "workspaceId");
        required(approverId, "approverId");
        Optional<Application> existing = applications.findByPreview(previewId, workspaceId);
        if (existing.isPresent()) return existing.get();
        var preview = previews.find(previewId, workspaceId)
                .orElseThrow(() -> new java.util.NoSuchElementException("quota repair preview not found"));
        Instant now = clock.instant();
        if (!now.isBefore(preview.expiresAt())) throw new StalePreviewException("quota repair preview has expired");
        if (!preview.applicable()) throw new InapplicablePreviewException("quota repair preview is not applicable");
        return applications.apply(preview, approverId, now);
    }

    private static void required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " required");
    }
}