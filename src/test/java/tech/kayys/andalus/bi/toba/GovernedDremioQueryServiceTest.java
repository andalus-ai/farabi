package tech.kayys.andalus.bi.toba;

import org.junit.jupiter.api.Test;
import tech.kayys.andalus.bi.query.*;
import tech.kayys.andalus.bi.security.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GovernedDremioQueryServiceTest {
    private GovernedDremioQueryService service(boolean permitted) {
        return new GovernedDremioQueryService(
            (actor, workspace) -> new SemanticCatalog(Map.of()),
            (actor, workspace, source) -> permitted,
            new SemanticSqlCompiler(),
            new DremioHttpGateway(HttpClient.newHttpClient(),
                URI.create("http://localhost:9047"), () -> "test", Duration.ofSeconds(1)));
    }

    @Test void deniesCrossWorkspace() {
        var actor = new AccessContext("alice", Set.of("other"));
        var query = new SemanticQuery("sales", List.of("sales.total"), List.of(), List.of(), 10);
        assertThrows(AccessDeniedException.class, () -> service(true).submit(actor, "ws", query));
    }

    @Test void deniesSourceWithoutPolicyGrant() {
        var actor = new AccessContext("alice", Set.of("ws"));
        var query = new SemanticQuery("sales", List.of("sales.total"), List.of(), List.of(), 10);
        assertThrows(AccessDeniedException.class, () -> service(false).submit(actor, "ws", query));
    }

    @Test void rejectsFiltersUntilDialectSupportsSafeValues() {
        var actor = new AccessContext("alice", Set.of("ws"));
        var query = new SemanticQuery("sales", List.of("sales.total"), List.of(),
            List.of(new SemanticQuery.Filter("region", SemanticQuery.Operator.EQ, "north")), 10);
        assertThrows(UnsupportedOperationException.class, () -> service(true).submit(actor, "ws", query));
    }
}
