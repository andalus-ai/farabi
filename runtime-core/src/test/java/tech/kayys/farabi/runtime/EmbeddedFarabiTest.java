package tech.kayys.farabi.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.QueryContracts.*;
import tech.kayys.farabi.engine.QueryPorts.*;

class EmbeddedFarabiTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC);

    @Test
    void composesPortsAndPreservesAuthorizePersistExecuteTransitionOrder() {
        List<String> events = new ArrayList<>();
        InMemoryRepository repository = new InMemoryRepository(events);
        Authorizer authorizer = request -> events.add("authorize");
        Compiler compiler = request -> {
            events.add("compile");
            return new Plan(request.sourceId(), "SELECT 1");
        };
        Executor executor = plan -> {
            events.add("execute");
            return new Submission("provider-job-1");
        };

        EmbeddedFarabi farabi = new EmbeddedFarabi(authorizer, compiler, executor, repository, CLOCK);
        Request request = request();

        Execution result = farabi.queries().submit(request);

        assertEquals(List.of("authorize", "compile", "persist", "execute", "transition", "find"), events);
        assertEquals(request.workspaceId(), result.workspaceId());
        assertEquals(request.subjectId(), result.subjectId());
        assertEquals(Status.SUBMITTED, result.status());
        assertEquals("provider-job-1", result.providerJobId());
        assertEquals(CLOCK.instant(), result.createdAt());
    }

    @Test
    void authorizationFailurePreventsCompilationPersistenceAndExecution() {
        List<String> events = new ArrayList<>();
        InMemoryRepository repository = new InMemoryRepository(events);
        EmbeddedFarabi farabi = new EmbeddedFarabi(
            request -> { events.add("authorize"); throw new SecurityException("denied"); },
            request -> { events.add("compile"); return new Plan(request.sourceId(), "SELECT 1"); },
            plan -> { events.add("execute"); return new Submission("job"); },
            repository, CLOCK);

        assertThrows(SecurityException.class, () -> farabi.queries().submit(request()));
        assertEquals(List.of("authorize"), events);
        assertTrue(repository.executions.isEmpty());
    }

    @Test
    void reportsReconciliationConflictWhenRemoteAcceptedButTransitionFails() {
        List<String> events = new ArrayList<>();
        ExecutionRepository conflictingRepository = new InMemoryRepository(events) {
            @Override
            public boolean transition(UUID id, String workspaceId, Status expected, Status next, String providerJobId) {
                events.add("transition-conflict");
                return false;
            }
        };
        EmbeddedFarabi farabi = new EmbeddedFarabi(
            request -> events.add("authorize"),
            request -> new Plan(request.sourceId(), "SELECT 1"),
            plan -> { events.add("remote-accepted"); return new Submission("accepted-job"); },
            conflictingRepository, CLOCK);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> farabi.queries().submit(request()));

        assertTrue(failure.getMessage().contains("remote accepted but local state conflicted; reconcile"));
        assertTrue(events.indexOf("persist") < events.indexOf("remote-accepted"));
        assertTrue(events.indexOf("remote-accepted") < events.indexOf("transition-conflict"));
    }

    private static Request request() {
        return new Request("workspace-a", "subject-a", "source-a", "revenue by month", Map.of());
    }

    private static class InMemoryRepository implements ExecutionRepository {
        final Map<UUID, Execution> executions = new HashMap<>();
        final List<String> events;

        InMemoryRepository(List<String> events) { this.events = events; }

        @Override
        public void create(Execution execution) {
            events.add("persist");
            executions.put(execution.id(), execution);
        }

        @Override
        public Optional<Execution> find(UUID id, String workspaceId) {
            events.add("find");
            Execution execution = executions.get(id);
            return execution != null && execution.workspaceId().equals(workspaceId)
                ? Optional.of(execution) : Optional.empty();
        }

        @Override
        public boolean transition(UUID id, String workspaceId, Status expected, Status next, String providerJobId) {
            events.add("transition");
            Execution current = executions.get(id);
            if (current == null || !current.workspaceId().equals(workspaceId) || current.status() != expected) {
                return false;
            }
            executions.put(id, new Execution(id, current.workspaceId(), current.subjectId(), current.sourceId(),
                providerJobId, next, current.createdAt(), CLOCK.instant()));
            return true;
        }
    }
}
