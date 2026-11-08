package tech.kayys.farabi.engine;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Provider-neutral semantic query request and resolved catalog metadata.
 * This contract deliberately contains no SQL, table names, or provider expressions.
 */
public final class SemanticQueryContracts {
    private SemanticQueryContracts() {}

    public enum FieldType { TEXT, DECIMAL, DATE, BOOLEAN }
    public enum Aggregation { SUM, AVG, MIN, MAX, COUNT }
    public enum Operator { EQ, NE, GT, GTE, LT, LTE, IN, NOT_IN }

    public record FieldRef(String name) {
        public FieldRef { QueryContracts.required(name, "field name"); }
    }

    public record Measure(FieldRef field, Aggregation aggregation, String alias) {
        public Measure {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(aggregation, "aggregation");
            QueryContracts.required(alias, "measure alias");
        }
    }

    /** A literal is represented as a typed Java value, never interpolated into query text. */
    public record Predicate(FieldRef field, Operator operator, FieldType valueType, Object value) {
        public Predicate {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(valueType, "valueType");
            Objects.requireNonNull(value, "value");
            boolean valid = switch (valueType) {
                case TEXT -> value instanceof String;
                case DECIMAL -> value instanceof BigDecimal;
                case DATE -> value instanceof LocalDate;
                case BOOLEAN -> value instanceof Boolean;
            };
            if (!valid) throw new IllegalArgumentException("predicate value does not match declared type");
            if ((operator == Operator.GT || operator == Operator.GTE || operator == Operator.LT || operator == Operator.LTE)
                    && valueType == FieldType.BOOLEAN)
                throw new IllegalArgumentException("ordered comparison is not supported for boolean fields");
        }
    }

    public record Query(
            String datasetId,
            List<FieldRef> dimensions,
            List<Measure> measures,
            List<Predicate> predicates,
            int limit
    ) {
        public Query {
            QueryContracts.required(datasetId, "datasetId");
            dimensions = List.copyOf(Objects.requireNonNull(dimensions, "dimensions"));
            measures = List.copyOf(Objects.requireNonNull(measures, "measures"));
            predicates = List.copyOf(Objects.requireNonNull(predicates, "predicates"));
            if (dimensions.isEmpty() && measures.isEmpty())
                throw new IllegalArgumentException("at least one dimension or measure is required");
            if (limit < 1 || limit > 10000) throw new IllegalArgumentException("limit must be 1..10000");
            if (dimensions.stream().distinct().count() != dimensions.size())
                throw new IllegalArgumentException("duplicate dimension");
            if (measures.stream().map(Measure::alias).distinct().count() != measures.size())
                throw new IllegalArgumentException("duplicate measure alias");
        }
    }

    public record CatalogField(FieldRef ref, FieldType type, boolean groupable, boolean aggregatable) {
        public CatalogField {
            Objects.requireNonNull(ref, "ref");
            Objects.requireNonNull(type, "type");
        }
    }

    /** The host implements this using its governed semantic catalog and authorization context. */
    public interface Catalog {
        CatalogField requireField(String workspaceId, String subjectId, String datasetId, FieldRef field);
        void authorizeDataset(String workspaceId, String subjectId, String datasetId);
    }
}
