package tech.kayys.andalus.bi.query.jobs;

import java.util.Optional;
import java.util.UUID;

/** Durable implementations must atomically compare status on transitions. */
public interface QueryJobStore {
    void create(QueryJob job);
    Optional<QueryJob> find(UUID id);
    boolean transition(UUID id, QueryJob.Status expected, QueryJob.Status next,
                       String providerJobId, java.time.Instant now);
}
