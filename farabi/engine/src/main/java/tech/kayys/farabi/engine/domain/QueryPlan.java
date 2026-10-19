package tech.kayys.farabi.engine.domain;

import java.util.Objects;

public record QueryPlan(String sourceId, String sql) {
    public QueryPlan {
        if (sourceId == null || sourceId.isBlank()) throw new IllegalArgumentException("sourceId required");
        if (sql == null || sql.isBlank()) throw new IllegalArgumentException("sql required");
    }
}
