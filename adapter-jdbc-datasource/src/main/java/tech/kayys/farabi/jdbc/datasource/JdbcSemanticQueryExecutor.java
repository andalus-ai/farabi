package tech.kayys.farabi.jdbc.datasource;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import tech.kayys.farabi.engine.SemanticQueryContracts.Query;
import tech.kayys.farabi.engine.SemanticQueryPlanCompiler;
import tech.kayys.farabi.engine.SemanticQueryService;

/**
 * Composes the governed semantic compiler with bounded JDBC execution.
 *
 * <p>This adapter is intended to be called only by SemanticQueryService after
 * its workspace/subject catalog authorization and semantic validation have
 * succeeded. Metadata resolution remains workspace/subject scoped.</p>
 */
public final class JdbcSemanticQueryExecutor implements SemanticQueryService.Executor {
    private final SemanticQueryPlanCompiler compiler;
    private final JdbcQueryExecutor executor;

    public JdbcSemanticQueryExecutor(SemanticQueryPlanCompiler compiler, JdbcQueryExecutor executor) {
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public List<Map<String, Object>> execute(String workspaceId, String subjectId, Query validatedQuery) {
        // The compiler resolves physical metadata using both security principals.
        var plan = compiler.compile(workspaceId, subjectId, validatedQuery);
        var result = executor.execute(plan);
        if (result.truncated()) {
            throw new IllegalStateException("JDBC result exceeded the configured execution row bound");
        }
        return result.rows();
    }
}
