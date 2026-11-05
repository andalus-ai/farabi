package tech.kayys.farabi.engine;

import java.time.Instant;
import java.util.Map;
import java.util.List;
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

    /** One positional JDBC parameter. Values are bound, never interpolated into SQL. */
    public record Parameter(int position, Object value, Integer jdbcType) {
        public Parameter {
            if (position < 1) throw new IllegalArgumentException("parameter position must be positive");
            if (jdbcType != null && jdbcType == java.sql.Types.NULL) {
                // Types.NULL is valid for explicit SQL NULL binding.
            }
            if (value != null && !(value instanceof String || value instanceof Boolean
                    || value instanceof Byte || value instanceof Short || value instanceof Integer
                    || value instanceof Long || value instanceof Float || value instanceof Double
                    || value instanceof java.math.BigDecimal || value instanceof java.math.BigInteger
                    || value instanceof java.time.LocalDate || value instanceof java.time.LocalTime
                    || value instanceof java.time.LocalDateTime || value instanceof java.time.OffsetDateTime
                    || value instanceof java.time.OffsetTime || value instanceof java.sql.Date
                    || value instanceof java.sql.Time || value instanceof java.sql.Timestamp
                    || value instanceof java.util.UUID || value instanceof byte[])) {
                throw new IllegalArgumentException("unsupported query parameter type: " + value.getClass().getName());
            }
            if (value instanceof byte[] bytes) value = bytes.clone();
        }
        @Override public Object value() { return value instanceof byte[] bytes ? bytes.clone() : value; }
    }

    public record Plan(String sourceId, String sql, List<Parameter> parameters) {
        public Plan {
            required(sourceId,"sourceId"); required(sql,"sql");
            parameters = List.copyOf(Objects.requireNonNull(parameters,"parameters"));
            int expected = 1;
            for (Parameter parameter : parameters) {
                Objects.requireNonNull(parameter,"parameter");
                if (parameter.position() != expected++) {
                    throw new IllegalArgumentException("query parameters must be contiguous and ordered from position 1");
                }
            }
        }
        public Plan(String sourceId, String sql) { this(sourceId, sql, List.of()); }
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
