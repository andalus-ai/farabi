package tech.kayys.farabi.engine.port;

import tech.kayys.farabi.engine.domain.QueryPlan;
import tech.kayys.farabi.engine.domain.QueryRequest;

@FunctionalInterface
public interface SemanticQueryCompiler {
    QueryPlan compile(QueryRequest request);
}
