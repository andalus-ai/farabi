package tech.kayys.andalus.bi.query.jobs;

import tech.kayys.andalus.bi.security.AccessContext;
import tech.kayys.andalus.bi.security.AccessDeniedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Ownership-aware job lifecycle. The store is trusted server-side state.
 * A client-provided provider job ID is never accepted as proof of ownership.
 */
public final class QueryJobService {
    private final QueryJobStore store;
    private final Clock clock;

    public QueryJobService(QueryJobStore store, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock);
    }

    public QueryJob create(AccessContext actor, String workspaceId, String sourceId, Duration ttl) {
        requireWorkspace(actor, workspaceId);
        if (ttl == null || ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("ttl must be positive and at most 24h");
        }
        Instant now = clock.instant();
        QueryJob job = new QueryJob(UUID.randomUUID(), workspaceId, actor.subjectId(), sourceId,
                null, QueryJob.Status.PENDING, now, now, now.plus(ttl));
        store.create(job);
        return job;
    }

    public QueryJob get(AccessContext actor, String workspaceId, UUID id) {
        requireWorkspace(actor, workspaceId);
        QueryJob job = store.find(Objects.requireNonNull(id))
                .orElseThrow(() -> new IllegalArgumentException("job not found"));
        if (!job.workspaceId().equals(workspaceId) || !job.subjectId().equals(actor.subjectId())) {
            throw new AccessDeniedException("job access denied");
        }
        if (job.expired(clock.instant())) throw new AccessDeniedException("job expired");
        return job;
    }

    public QueryJob transition(AccessContext actor, String workspaceId, UUID id,
                               QueryJob.Status expected, QueryJob.Status next, String providerJobId) {
        QueryJob job = get(actor, workspaceId, id);
        if (!allowed(expected, next) || job.status() != expected) {
            throw new IllegalStateException("invalid job transition");
        }
        if (!store.transition(id, expected, next, providerJobId, clock.instant())) {
            throw new IllegalStateException("concurrent job transition");
        }
        return get(actor, workspaceId, id);
    }

    private static boolean allowed(QueryJob.Status from, QueryJob.Status to) {
        return switch (from) {
            case PENDING -> to == QueryJob.Status.SUBMITTED || to == QueryJob.Status.FAILED
                    || to == QueryJob.Status.CANCELLED;
            case SUBMITTED -> to == QueryJob.Status.RUNNING || to == QueryJob.Status.FAILED
                    || to == QueryJob.Status.CANCELLED;
            case RUNNING -> to == QueryJob.Status.SUCCEEDED || to == QueryJob.Status.FAILED
                    || to == QueryJob.Status.CANCELLED;
            default -> false;
        };
    }

    private static void requireWorkspace(AccessContext actor, String workspaceId) {
        Objects.requireNonNull(actor, "authenticated actor required").requireWorkspace(workspaceId);
    }
}
