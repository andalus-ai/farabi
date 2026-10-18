package tech.kayys.andalus.bi.query.jobs;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable event describing a server-side query job lifecycle decision. */
public record QueryExecutionAudit(UUID eventId, UUID jobId, String workspaceId,
                                  String subjectId, String eventType, Instant occurredAt) {
    public QueryExecutionAudit {
        Objects.requireNonNull(eventId);
        Objects.requireNonNull(jobId);
        Objects.requireNonNull(workspaceId);
        Objects.requireNonNull(subjectId);
        Objects.requireNonNull(eventType);
        Objects.requireNonNull(occurredAt);
    }
}
