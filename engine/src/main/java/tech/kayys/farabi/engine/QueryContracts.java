package tech.kayys.farabi.engine;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable engine-owned query contracts. */
public final class QueryContracts {
    private QueryContracts() {}
    public record Request(String workspaceId, String subjectId, String sourceId, String semanticQuery, Map<String,Object> parameters) {
        public Request {
            required(workspaceId,"workspaceId"); required(subjectId,"subjectId"); required(sourceId,"sourceId"); required(semanticQuery,"semanticQuery");
            parameters = Map.copyOf(Objects.requireNonNull(parameters,"parameters"));
        }
    }
    public record Plan(String sourceId, String sql) {
        public Plan { required(sourceId,"sourceId"); required(sql,"sql"); }
    }
    public record Submission(String providerJobId) {
        public Submission { required(providerJobId,"providerJobId"); }
    }
    public enum Status { PENDING, SUBMITTED, RUNNING, SUCCEEDED, FAILED, CANCELLED }
    public record Execution(UUID id, String workspaceId, String subjectId, String sourceId, String providerJobId, Status status, Instant createdAt, Instant updatedAt) {
        public Execution {
            Objects.requireNonNull(id); required(workspaceId,"workspaceId"); required(subjectId,"subjectId"); required(sourceId,"sourceId");
            Objects.requireNonNull(status); Objects.requireNonNull(createdAt); Objects.requireNonNull(updatedAt);
            if(updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("updatedAt precedes createdAt");
            if(providerJobId != null) required(providerJobId,"providerJobId");
        }
    }
    public static void required(String value,String name) {
        if(value == null || value.isBlank()) throw new IllegalArgumentException(name+" required");
    }
}
