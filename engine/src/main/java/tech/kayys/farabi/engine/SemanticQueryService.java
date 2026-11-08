package tech.kayys.farabi.engine;

import java.time.Instant;
import java.util.*;
import tech.kayys.farabi.engine.SemanticQueryContracts.Query;

/**
 * Application service for governed semantic query execution.
 *
 * The engine validates the request and delegates execution through a port.
 * Provider adapters receive semantic contracts only; SQL generation and
 * connection handling remain outside the engine.
 */
public final class SemanticQueryService {
    @FunctionalInterface
    public interface Executor {
        List<Map<String, Object>> execute(String workspaceId, String subjectId, Query validatedQuery);
    }

    public record Result(
            String datasetId,
            List<Map<String, Object>> rows,
            int requestedLimit,
            Instant executedAt
    ) {
        public Result {
            QueryContracts.required(datasetId, "datasetId");
            Objects.requireNonNull(rows, "rows");
            rows = List.copyOf(rows.stream()
                    .map(row -> Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(row))))
                    .toList());
            if (requestedLimit < 1) throw new IllegalArgumentException("requestedLimit must be positive");
            Objects.requireNonNull(executedAt, "executedAt");
            if (rows.size() > requestedLimit)
                throw new IllegalArgumentException("executor returned more rows than the requested limit");
        }
    }

    private final SemanticQueryValidator validator;
    private final Executor executor;

    public SemanticQueryService(SemanticQueryValidator validator, Executor executor) {
        this.validator = Objects.requireNonNull(validator, "validator");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public Result execute(String workspaceId, String subjectId, Query query) {
        // Validation (including dataset authorization) must complete before
        // the execution adapter is invoked.
        Query validated = validator.validate(workspaceId, subjectId, query);
        List<Map<String, Object>> rows = Objects.requireNonNull(
                executor.execute(workspaceId, subjectId, validated),
                "semantic executor returned null rows");
        if (rows.size() > validated.limit())
            throw new IllegalStateException("semantic executor exceeded the requested row limit");
        return new Result(validated.datasetId(), rows, validated.limit(), Instant.now());
    }
}
