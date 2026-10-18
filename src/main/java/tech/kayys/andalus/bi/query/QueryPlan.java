package tech.kayys.andalus.bi.query;

import java.util.List;

/** Immutable prepared SQL plan; values are bound separately from SQL text. */
public record QueryPlan(String sourceId, String sql, List<Object> parameters, int limit) {
    public QueryPlan {
        if (sourceId == null || sourceId.isBlank() || sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("sourceId and SQL required");
        }
        parameters = List.copyOf(parameters);
    }
}
