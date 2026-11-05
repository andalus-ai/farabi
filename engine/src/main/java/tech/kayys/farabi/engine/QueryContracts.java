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
    /** Legacy SQL-only plan retained for existing adapters during migration. */
    public record Plan(String sourceId, String sql) {
        public Plan { required(sourceId,"sourceId"); required(sql,"sql"); }
    }

    public enum ParameterType { TEXT, DECIMAL, DATE }

    /** A typed value bound to a placeholder; never rendered into SQL text. */
    public record QueryParameter(String name, ParameterType type, Object value) {
        public QueryParameter {
            required(name, "name");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(value, "value");
            boolean valid = switch (type) {
                case TEXT -> value instanceof String;
                case DECIMAL -> value instanceof java.math.BigDecimal;
                case DATE -> value instanceof java.time.LocalDate;
            };
            if (!valid) throw new IllegalArgumentException("parameter value does not match type");
        }
    }

    /** Additive parameterized plan; legacy Plan remains available to existing executors. */
    public record ParameterizedPlan(
        String sourceId,
        String dialectId,
        String sqlTemplate,
        java.util.List<QueryParameter> parameters
    ) {
        public ParameterizedPlan {
            required(sourceId, "sourceId");
            required(dialectId, "dialectId");
            required(sqlTemplate, "sqlTemplate");
            parameters = java.util.List.copyOf(Objects.requireNonNull(parameters, "parameters"));
        }
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
