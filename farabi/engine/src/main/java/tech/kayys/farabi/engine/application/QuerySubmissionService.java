package tech.kayys.farabi.engine.application;

import java.util.Objects;
import tech.kayys.farabi.engine.domain.QueryPlan;
import tech.kayys.farabi.engine.domain.QueryRequest;
import tech.kayys.farabi.engine.domain.QuerySubmission;
import tech.kayys.farabi.engine.port.QueryAuthorizer;
import tech.kayys.farabi.engine.port.QueryExecutor;
import tech.kayys.farabi.engine.port.SemanticQueryCompiler;

/** Framework-neutral use case. Caller supplies policy, semantic compiler and execution adapters. */
public final class QuerySubmissionService {
    private final QueryAuthorizer authorizer;
    private final SemanticQueryCompiler compiler;
    private final QueryExecutor executor;

    public QuerySubmissionService(QueryAuthorizer authorizer, SemanticQueryCompiler compiler, QueryExecutor executor) {
        this.authorizer = Objects.requireNonNull(authorizer);
        this.compiler = Objects.requireNonNull(compiler);
        this.executor = Objects.requireNonNull(executor);
    }

    public QuerySubmission submit(QueryRequest request) {
        Objects.requireNonNull(request);
        authorizer.authorize(request); // fail closed; never compile/execute before authorization
        QueryPlan plan = Objects.requireNonNull(compiler.compile(request), "compiler returned null");
        if (!plan.sourceId().equals(request.sourceId()))
            throw new IllegalStateException("compiled plan source mismatch");
        return Objects.requireNonNull(executor.submit(plan), "executor returned null");
    }
}
