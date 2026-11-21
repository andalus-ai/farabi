package tech.kayys.farabi.dremio;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.DataSourceContracts;
import tech.kayys.farabi.engine.QueryContracts;
import static org.junit.jupiter.api.Assertions.*;

class DremioSourceProviderTest {
    @Test void exposesOnlyImplementedCapabilitiesAndProviderPorts() {
        var descriptor = new DataSourceContracts.Descriptor("source-1", "dremio", "Warehouse", "v3");
        var catalog = new DataSourceContracts.Catalog() {
            public List<DataSourceContracts.CatalogEntry> catalogs() { return List.of(); }
            public List<DataSourceContracts.CatalogEntry> schemas(String catalog) { return List.of(); }
            public List<DataSourceContracts.CatalogEntry> relations(String catalog, String schema) { return List.of(); }
            public DataSourceContracts.RelationMetadata describe(String relationId) {
                throw new UnsupportedOperationException("not present in test catalog");
            }
        };
        var executor = new DremioSourceProvider(descriptor, catalog,
                new DremioExecutor(HttpClient.newHttpClient(), new ObjectMapper(),
                        URI.create("https://dremio.example"), "token"),
                new DremioJobProvider(HttpClient.newHttpClient(), new ObjectMapper(),
                        URI.create("https://dremio.example"), "token"));

        assertEquals(descriptor, executor.descriptor());
        assertSame(catalog, executor.catalog());
        assertTrue(executor.capabilities().supports(DataSourceContracts.Feature.CANCELLATION));
        assertTrue(executor.capabilities().supports(DataSourceContracts.Feature.RESULT_PAGING));
        assertFalse(executor.capabilities().supports(DataSourceContracts.Feature.PARAMETERIZED_QUERIES));
        assertEquals(DataSourceContracts.Health.UNKNOWN, executor.health());
        assertThrows(UnsupportedOperationException.class, () -> executor.parameterizedExecutor().submit(
                new QueryContracts.ParameterizedPlan("source-1", "dremio", "select ?", List.of())));
    }

    @Test void rejectsDescriptorForAnotherProvider() {
        var descriptor = new DataSourceContracts.Descriptor("source-1", "jdbc", "Warehouse", "v1");
        var catalog = new DataSourceContracts.Catalog() {
            public List<DataSourceContracts.CatalogEntry> catalogs() { return List.of(); }
            public List<DataSourceContracts.CatalogEntry> schemas(String catalog) { return List.of(); }
            public List<DataSourceContracts.CatalogEntry> relations(String catalog, String schema) { return List.of(); }
            public DataSourceContracts.RelationMetadata describe(String relationId) {
                throw new UnsupportedOperationException();
            }
        };
        assertThrows(IllegalArgumentException.class, () -> new DremioSourceProvider(descriptor, catalog,
                new DremioExecutor(HttpClient.newHttpClient(), new ObjectMapper(),
                        URI.create("https://dremio.example"), "token"),
                new DremioJobProvider(HttpClient.newHttpClient(), new ObjectMapper(),
                        URI.create("https://dremio.example"), "token")));
    }
}
