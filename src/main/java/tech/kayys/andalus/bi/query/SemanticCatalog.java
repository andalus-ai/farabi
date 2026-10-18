package tech.kayys.andalus.bi.query;

import tech.kayys.andalus.bi.semantic.SemanticMetric;
import java.util.Map;
import java.util.Objects;

/**
 * Trusted, workspace-authorized catalog. Physical identifiers must come from
 * an administrator-controlled catalog, never from agent-generated SQL.
 */
public record SemanticCatalog(Map<String, Source> sources) {
    public record Source(String sourceId, String tableName,
                         Map<String, SemanticMetric> metrics,
                         Map<String, String> dimensions) {
        public Source {
            if (sourceId == null || sourceId.isBlank()) throw new IllegalArgumentException("sourceId required");
            if (tableName == null || tableName.isBlank()) throw new IllegalArgumentException("tableName required");
            metrics = Map.copyOf(Objects.requireNonNull(metrics));
            dimensions = Map.copyOf(Objects.requireNonNull(dimensions));
        }
    }

    public SemanticCatalog {
        sources = Map.copyOf(Objects.requireNonNull(sources));
    }

    public Source requireSource(String sourceId) {
        Source source = sources.get(sourceId);
        if (source == null) throw new IllegalArgumentException("unknown source: " + sourceId);
        return source;
    }
}
