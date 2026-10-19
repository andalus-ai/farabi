package tech.kayys.farabi.engine.port;

import tech.kayys.farabi.engine.domain.QueryExecution;
import java.util.Optional;
import java.util.UUID;

public interface QueryExecutionRepository {
    void create(QueryExecution execution);
    Optional<QueryExecution> find(UUID id);
    /** Compare-and-set transition; false means the expected state no longer matches. */
    boolean transition(UUID id, QueryExecution.Status expected, QueryExecution.Status next,
                       String providerJobId);
}
