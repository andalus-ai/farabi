package tech.kayys.farabi.engine;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Append-only operator and worker audit events for storage inventory operations. */
public final class DatasetStorageInventoryAuditPorts {
    private DatasetStorageInventoryAuditPorts() {}
    public enum EventType { SUBMITTED, SUCCEEDED, RETRY_SCHEDULED, FAILED }
    public record Event(UUID id, String workspaceId, UUID jobId, String actorId, EventType type,
            int attempt, UUID reportId, String errorCode, Instant occurredAt) {
        public Event {
            Objects.requireNonNull(id); QueryContracts.required(workspaceId, "workspaceId");
            Objects.requireNonNull(jobId); QueryContracts.required(actorId, "actorId");
            Objects.requireNonNull(type); Objects.requireNonNull(occurredAt);
            if (attempt < 0) throw new IllegalArgumentException("attempt cannot be negative");
        }
    }
    public interface AuditLog { void append(Event event); }
}
