package tech.kayys.farabi.engine.domain;

import java.util.Map;
import java.util.Objects;

public record QueryRequest(String workspaceId, String subjectId, String sourceId,
                           String semanticQuery, Map<String, Object> parameters) {
    public QueryRequest {
        require(workspaceId, "workspaceId");
        require(subjectId, "subjectId");
        require(sourceId, "sourceId");
        require(semanticQuery, "semanticQuery");
        parameters = Map.copyOf(Objects.requireNonNull(parameters, "parameters"));
    }
    private static void require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " required");
    }
}
