package tech.kayys.andalus.bi.query.jobs;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record QueryJob(UUID id, String workspaceId, String subjectId, String sourceId,
                       String providerJobId, Status status,
                       Instant createdAt, Instant updatedAt, Instant expiresAt) {
    public enum Status { PENDING, SUBMITTED, RUNNING, SUCCEEDED, FAILED, CANCELLED }

    public QueryJob {
        Objects.requireNonNull(id);
        workspaceId = required(workspaceId);
        subjectId = required(subjectId);
        sourceId = required(sourceId);
        Objects.requireNonNull(status);
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(updatedAt);
        Objects.requireNonNull(expiresAt);
        if (!expiresAt.isAfter(createdAt)) throw new IllegalArgumentException("expiry must follow creation");
        if (providerJobId != null && !providerJobId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("invalid provider job id");
        }
    }

    public boolean expired(Instant now) { return !now.isBefore(expiresAt); }

    private static String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("required field missing");
        return value.trim();
    }
}
