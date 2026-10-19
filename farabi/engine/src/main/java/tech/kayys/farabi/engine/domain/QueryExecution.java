package tech.kayys.farabi.engine.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record QueryExecution(UUID id, String workspaceId, String subjectId, String sourceId,
                             String providerJobId, Status status, Instant createdAt, Instant updatedAt) {
    public enum Status { PENDING, SUBMITTED, RUNNING, SUCCEEDED, FAILED, CANCELLED }
    public QueryExecution {
        Objects.requireNonNull(id, "id");
        require(workspaceId, "workspaceId"); require(subjectId, "subjectId"); require(sourceId, "sourceId");
        Objects.requireNonNull(status, "status"); Objects.requireNonNull(createdAt, "createdAt"); Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("updatedAt before createdAt");
        if (providerJobId != null && providerJobId.isBlank()) throw new IllegalArgumentException("providerJobId must be null or non-blank");
    }
    private static void require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " required");
    }
    public boolean terminal() { return status == Status.SUCCEEDED || status == Status.FAILED || status == Status.CANCELLED; }
}
