package tech.kayys.andalus.bi.query;

import java.util.List;
import java.util.Objects;

/** Source-independent semantic query. Only governed metric and dimension IDs are accepted. */
public record SemanticQuery(
        String sourceId,
        List<String> metricIds,
        List<String> dimensions,
        List<Filter> filters,
        int limit) {

    public enum Operator { EQ, NE, GT, GTE, LT, LTE }

    public record Filter(String fieldId, Operator operator, Object value) {
        public Filter {
            if (fieldId == null || fieldId.isBlank()) throw new IllegalArgumentException("fieldId required");
            Objects.requireNonNull(operator, "operator");
            if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
                throw new IllegalArgumentException("filter value must be a scalar");
            }
        }
    }

    public SemanticQuery {
        if (sourceId == null || sourceId.isBlank()) throw new IllegalArgumentException("sourceId required");
        metricIds = List.copyOf(Objects.requireNonNull(metricIds, "metricIds"));
        dimensions = List.copyOf(Objects.requireNonNull(dimensions, "dimensions"));
        filters = List.copyOf(Objects.requireNonNull(filters, "filters"));
        if (metricIds.isEmpty()) throw new IllegalArgumentException("at least one metric required");
        if (metricIds.size() > 32 || dimensions.size() > 16 || filters.size() > 64) {
            throw new IllegalArgumentException("query exceeds complexity limits");
        }
        if (limit < 1 || limit > 10000) throw new IllegalArgumentException("limit must be 1..10000");
        if (metricIds.stream().anyMatch(s -> s == null || s.isBlank())
                || dimensions.stream().anyMatch(s -> s == null || s.isBlank())) {
            throw new IllegalArgumentException("metric and dimension IDs must not be blank");
        }
    }
}
