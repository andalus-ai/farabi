package tech.kayys.andalus.bi.query.jobs;

import org.junit.jupiter.api.Test;
import tech.kayys.andalus.bi.security.AccessContext;
import tech.kayys.andalus.bi.security.AccessDeniedException;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class QueryJobServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private static final class MemoryStore implements QueryJobStore {
        private final Map<UUID, QueryJob> jobs = new HashMap<>();
        @Override public void create(QueryJob job) { jobs.put(job.id(), job); }
        @Override public Optional<QueryJob> find(UUID id) { return Optional.ofNullable(jobs.get(id)); }
        @Override public boolean transition(UUID id, QueryJob.Status expected, QueryJob.Status next,
                                            String providerId, Instant now) {
            QueryJob old = jobs.get(id);
            if (old == null || old.status() != expected || old.expired(now)) return false;
            jobs.put(id, new QueryJob(id, old.workspaceId(), old.subjectId(), old.sourceId(),
                    providerId == null ? old.providerJobId() : providerId, next,
                    old.createdAt(), now, old.expiresAt()));
            return true;
        }
    }

    @Test void lifecycleAndOwnership() {
        var service = new QueryJobService(new MemoryStore(), CLOCK);
        var alice = new AccessContext("alice", Set.of("ws"));
        var bob = new AccessContext("bob", Set.of("ws"));
        var job = service.create(alice, "ws", "sales", Duration.ofMinutes(30));
        assertEquals(QueryJob.Status.PENDING, job.status());
        assertThrows(AccessDeniedException.class, () -> service.get(bob, "ws", job.id()));
        assertThrows(AccessDeniedException.class, () -> service.get(alice, "other", job.id()));
        var submitted = service.transition(alice, "ws", job.id(),
                QueryJob.Status.PENDING, QueryJob.Status.SUBMITTED, "dremio_job1");
        assertEquals("dremio_job1", submitted.providerJobId());
        assertEquals(QueryJob.Status.RUNNING, service.transition(alice, "ws", job.id(),
                QueryJob.Status.SUBMITTED, QueryJob.Status.RUNNING, null).status());
        assertEquals(QueryJob.Status.SUCCEEDED, service.transition(alice, "ws", job.id(),
                QueryJob.Status.RUNNING, QueryJob.Status.SUCCEEDED, null).status());
        assertThrows(IllegalStateException.class, () -> service.transition(alice, "ws", job.id(),
                QueryJob.Status.SUCCEEDED, QueryJob.Status.RUNNING, null));
    }

    @Test void validatesTtl() {
        var service = new QueryJobService(new MemoryStore(), CLOCK);
        var alice = new AccessContext("alice", Set.of("ws"));
        assertThrows(IllegalArgumentException.class, () ->
                service.create(alice, "ws", "sales", Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () ->
                service.create(alice, "ws", "sales", Duration.ofDays(2)));
    }
}
