package tech.kayys.farabi.dremio;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.DataSourceContracts.EntryKind;
import tech.kayys.farabi.engine.DataSourceContracts.LogicalType;
import static org.junit.jupiter.api.Assertions.*;

class DremioCatalogTest {
    private static final URI BASE = URI.create("https://dremio.example");

    @Test void discoversSourcesFoldersDatasetsAndFieldsThroughCatalogApi() {
        Map<String, String> responses = new HashMap<>();
        responses.put("/api/v3/catalog", """
                [
                  {"id":"source-1","name":"warehouse","type":"SOURCE"},
                  {"id":"space-1","name":"analytics","type":"SPACE"},
                  {"id":"dataset-ignored","name":"orders","type":"PHYSICAL_DATASET"}
                ]
                """);
        responses.put("/api/v3/catalog/source-1", """
                {"id":"source-1","name":"warehouse","type":"SOURCE","children":[
                  {"id":"folder-1","name":"analytics","type":"FOLDER"},
                  {"id":"table-root","name":"events","type":"PHYSICAL_DATASET"}
                ]}
                """);
        responses.put("/api/v3/catalog/folder-1", """
                {"id":"folder-1","name":"analytics","type":"FOLDER","children":[
                  {"id":"table-1","name":"orders","type":"PHYSICAL_DATASET"},
                  {"id":"view-1","name":"monthly_sales","type":"VIRTUAL_DATASET"},
                  {"id":"nested-folder","name":"archive","type":"FOLDER"}
                ]}
                """);
        responses.put("/api/v3/catalog/table-1", """
                {"id":"table-1","type":"PHYSICAL_DATASET","path":["warehouse","analytics","orders"],
                 "fields":[
                   {"name":"order_id","type":{"name":"BIGINT"},"nullable":false},
                   {"name":"amount","type":{"name":"DECIMAL"},"nullable":true},
                   {"name":"attributes","type":{"name":"MAP"},"nullable":true}
                 ]}
                """);
        var catalog = new DremioCatalog(new ObjectMapper(), BASE, (uri, token) -> {
            assertEquals("Bearer test-token", "Bearer " + token);
            String body = responses.get(uri.getRawPath());
            return body == null ? new DremioCatalog.Response(404, "") : new DremioCatalog.Response(200, body);
        }, "test-token");

        var catalogs = catalog.catalogs();
        assertEquals(2, catalogs.size());
        assertEquals(EntryKind.CATALOG, catalogs.get(0).kind());

        var schemas = catalog.schemas("source-1");
        assertEquals(1, schemas.size());
        assertEquals("folder-1", schemas.get(0).id());
        assertEquals(EntryKind.SCHEMA, schemas.get(0).kind());

        var relations = catalog.relations("source-1", "folder-1");
        assertEquals(2, relations.size());
        assertTrue(relations.stream().anyMatch(item -> item.id().equals("table-1") && item.kind() == EntryKind.TABLE));
        assertTrue(relations.stream().anyMatch(item -> item.id().equals("view-1") && item.kind() == EntryKind.VIEW));

        var described = catalog.describe("table-1");
        assertEquals("orders", described.name());
        assertEquals("warehouse", described.catalog());
        assertEquals("analytics", described.schema());
        assertEquals(3, described.columns().size());
        assertEquals(LogicalType.INTEGER, described.columns().get(0).type());
        assertFalse(described.columns().get(0).nullable());
        assertEquals(LogicalType.DECIMAL, described.columns().get(1).type());
        assertEquals(LogicalType.JSON, described.columns().get(2).type());
    }

    @Test void mapsAuthorizationAndMissingEntityFailuresExplicitly() {
        var forbidden = new DremioCatalog(new ObjectMapper(), BASE,
                (uri, token) -> new DremioCatalog.Response(403, ""), "token");
        assertThrows(SecurityException.class, forbidden::catalogs);

        var missing = new DremioCatalog(new ObjectMapper(), BASE,
                (uri, token) -> new DremioCatalog.Response(404, ""), "token");
        assertThrows(IllegalArgumentException.class, () -> missing.schemas("missing"));
    }

    @Test void rejectsMalformedCatalogCollectionAndNonTlsEndpoints() {
        var malformed = new DremioCatalog(new ObjectMapper(), BASE,
                (uri, token) -> new DremioCatalog.Response(200, "{\"unexpected\":true}"), "token");
        assertThrows(IllegalStateException.class, malformed::catalogs);

        assertThrows(IllegalArgumentException.class, () -> new DremioCatalog(
                new ObjectMapper(), URI.create("http://dremio.example"),
                (uri, token) -> new DremioCatalog.Response(200, "[]"), "token"));
    }
}
