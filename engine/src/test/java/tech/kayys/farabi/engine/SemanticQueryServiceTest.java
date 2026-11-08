package tech.kayys.farabi.engine;

import java.util.*;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.SemanticQueryContracts.*;
import static org.junit.jupiter.api.Assertions.*;

class SemanticQueryServiceTest {
    @Test void validatesBeforeCallingExecutionPortAndReturnsImmutableRows() {
        var calls = new ArrayList<String>();
        var catalog = new Catalog() {
            public void authorizeDataset(String w, String s, String d) { calls.add("authorize"); }
            public CatalogField requireField(String w, String s, String d, FieldRef f) {
                calls.add("resolve:" + f.name());
                return new CatalogField(f, FieldType.TEXT, true, false);
            }
        };
        var query = new Query("orders", List.of(new FieldRef("region")), List.of(), List.of(), 20);
        var service = new SemanticQueryService(new SemanticQueryValidator(catalog), (w, s, q) -> {
            calls.add("execute");
            assertEquals("tenant-a", w);
            assertEquals("analyst-1", s);
            assertSame(query, q);
            return List.of(Map.of("region", "west"));
        });

        var result = service.execute("tenant-a", "analyst-1", query);
        assertEquals(List.of("authorize", "resolve:region", "execute"), calls);
        assertEquals("orders", result.datasetId());
        assertEquals(20, result.requestedLimit());
        assertThrows(UnsupportedOperationException.class, () -> result.rows().get(0).put("region", "east"));
        assertThrows(UnsupportedOperationException.class, () -> result.rows().add(Map.of()));
    }

    @Test void neverCallsExecutorWhenCatalogAuthorizationFails() {
        var service = new SemanticQueryService(new SemanticQueryValidator(new Catalog() {
            public void authorizeDataset(String w, String s, String d) { throw new SecurityException("denied"); }
            public CatalogField requireField(String w, String s, String d, FieldRef f) { fail("field lookup must not run"); return null; }
        }), (w, s, q) -> { fail("executor must not run"); return List.of(); });

        var query = new Query("restricted", List.of(new FieldRef("salary")), List.of(), List.of(), 10);
        assertThrows(SecurityException.class, () -> service.execute("ws", "user", query));
    }

    @Test void rejectsExecutorReturningMoreRowsThanRequested() {
        var catalog = new Catalog() {
            public void authorizeDataset(String w, String s, String d) {}
            public CatalogField requireField(String w, String s, String d, FieldRef f) {
                return new CatalogField(f, FieldType.TEXT, true, false);
            }
        };
        var service = new SemanticQueryService(new SemanticQueryValidator(catalog),
                (w, s, q) -> List.of(Map.of("region", "a"), Map.of("region", "b")));
        var query = new Query("orders", List.of(new FieldRef("region")), List.of(), List.of(), 1);
        assertThrows(IllegalStateException.class, () -> service.execute("ws", "user", query));
    }

    @Test void rejectsNullExecutionResult() {
        var catalog = new Catalog() {
            public void authorizeDataset(String w, String s, String d) {}
            public CatalogField requireField(String w, String s, String d, FieldRef f) {
                return new CatalogField(f, FieldType.TEXT, true, false);
            }
        };
        var service = new SemanticQueryService(new SemanticQueryValidator(catalog), (w, s, q) -> null);
        var query = new Query("orders", List.of(new FieldRef("region")), List.of(), List.of(), 1);
        assertThrows(NullPointerException.class, () -> service.execute("ws", "user", query));
    }
}
