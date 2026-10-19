package tech.kayys.farabi.engine.port;

import tech.kayys.farabi.engine.domain.QueryPlan;
import tech.kayys.farabi.engine.domain.QuerySubmission;

@FunctionalInterface
public interface QueryExecutor {
    QuerySubmission submit(QueryPlan plan);
}
