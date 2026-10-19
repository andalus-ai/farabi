package tech.kayys.andalus.bi.query.jobs;

import org.junit.jupiter.api.Test;
import tech.kayys.andalus.bi.query.*;
import tech.kayys.andalus.bi.security.*;
import tech.kayys.andalus.bi.toba.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class QueryExecutionCoordinatorTest {
    private static final class Store implements QueryJobStore {
        private final Map<UUID, QueryJob> values = new HashMap<>();
        @Override public void create(QueryJob job) { values.put(job.id(), job); }
        @Override public Optional<QueryJob> find(UUID id) { return Optional.ofNullable(values.get(id)); }
        @Override public boolean transition(UUID id, QueryJob.Status expected,
                QueryJob.Status next, String providerId, Instant now) { return false; }
    }

    @Test void deniesUnauthorizedSourceWithoutPersistingJob() {
        Store store = new Store();
        var jobs = new QueryJobService(store, Clock.systemUTC());
        var gateway = new DremioHttpGateway(HttpClient.newHttpClient(),
                URI.create("http://localhost:9047"), () -> "token", Duration.ofSeconds(1));
        GovernedDremioQueryService.DatasetPolicy deny = (actor, workspace, source) -> false;
        var governed = new GovernedDremioQueryService(
                (actor, workspace) -> new SemanticCatalog(Map.of()),
                deny, new SemanticSqlCompiler(), gateway);
        var coordinator = new QueryExecutionCoordinator(jobs, governed, gateway, deny);
        var actor = new AccessContext("alice", Set.of("ws"));
        var query = new SemanticQuery("sales", List.of("total"), List.of(), List.of(), 10);
        assertThrows(AccessDeniedException.class, () ->
                coordinator.submit(actor, "ws", query, Duration.ofMinutes(10)));
        assertTrue(store.values.isEmpty());
    }

    @Test void rejectsCrossWorkspaceBeforeCreatingJob() {
        Store store = new Store();
        var jobs = new QueryJobService(store, Clock.systemUTC());
        var gateway = new DremioHttpGateway(HttpClient.newHttpClient(),
                URI.create("http://localhost:9047"), () -> "token", Duration.ofSeconds(1));
        GovernedDremioQueryService.DatasetPolicy allow = (actor, workspace, source) -> true;
        var governed = new GovernedDremioQueryService(
                (actor, workspace) -> new SemanticCatalog(Map.of()),
                allow, new SemanticSqlCompiler(), gateway);
        var coordinator = new QueryExecutionCoordinator(jobs, governed, gateway, allow);
        var actor = new AccessContext("alice", Set.of("other"));
        var query = new SemanticQuery("sales", List.of("total"), List.of(), List.of(), 10);
        assertThrows(AccessDeniedException.class, () ->
                coordinator.submit(actor, "ws", query, Duration.ofMinutes(10)));
        assertTrue(store.values.isEmpty());
    }
}
