package tech.kayys.farabi.dremio;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.DataSourceContracts;
import tech.kayys.farabi.engine.QueryContracts;
import static org.junit.jupiter.api.Assertions.*;

class DremioSourceProviderTest {
    private static DataSourceContracts.ConnectionDefinition definition() {
        return new DataSourceContracts.ConnectionDefinition("source-1", "workspace-a", "Warehouse",
                DataSourceContracts.ProviderKind.DREMIO, "dremio", URI.create("https://dremio.example"),
                null, null, Map.of(), null, true, Instant.EPOCH);
    }

    private static DremioSourceProvider.Source source(String providerId) {
        var descriptor = new DataSourceContracts.Descriptor("source-1", providerId, "Warehouse", "v3");
        var catalog = new DataSourceContracts.Catalog() {
            public List<DataSourceContracts.CatalogEntry> catalogs() { return List.of(); }
            public List<DataSourceContracts.CatalogEntry> schemas(String catalog) { return List.of(); }
            public List<DataSourceContracts.CatalogEntry> relations(String catalog, String schema) { return List.of(); }
            public DataSourceContracts.RelationMetadata describe(String relationId) {
                throw new UnsupportedOperationException("not present in test catalog");
            }
        };
        return new DremioSourceProvider.Source(descriptor, catalog,
                new DremioExecutor(HttpClient.newHttpClient(), new ObjectMapper(),
                        URI.create("https://dremio.example"), "token"),
                new DremioJobProvider(HttpClient.newHttpClient(), new ObjectMapper(),
                        URI.create("https://dremio.example"), "token"));
    }

    @Test void resolvesSourceSpecificPortsAndAdvertisesOnlyImplementedCapabilities() {
        var source = source("dremio");
        var provider = new DremioSourceProvider(definition -> source);
        var definition = definition();

        assertEquals(source.descriptor(), provider.descriptor(definition));
        assertSame(source.catalog(), provider.catalog(definition));
        assertTrue(provider.capabilities(definition).supports(DataSourceContracts.Feature.CANCELLATION));
        assertTrue(provider.capabilities(definition).supports(DataSourceContracts.Feature.RESULT_PAGING));
        assertFalse(provider.capabilities(definition).supports(DataSourceContracts.Feature.PARAMETERIZED_QUERIES));
        assertEquals(DataSourceContracts.Health.UNKNOWN, provider.health(definition).health());
        assertThrows(UnsupportedOperationException.class, () -> provider.parameterizedExecutor(definition).submit(
                new QueryContracts.ParameterizedPlan("source-1", "dremio", "select ?", List.of())));
    }

    @Test void rejectsFactoryDescriptorForAnotherProvider() {
        var provider = new DremioSourceProvider(definition -> source("jdbc"));
        assertThrows(IllegalStateException.class, () -> provider.descriptor(definition()));
    }

    @Test void rejectsNonDremioConnectionDefinition() {
        var provider = new DremioSourceProvider(definition -> source("dremio"));
        var invalid = new DataSourceContracts.ConnectionDefinition("source-1", "workspace-a", "Warehouse",
                DataSourceContracts.ProviderKind.JDBC, "jdbc",
                URI.create("jdbc:postgresql://localhost/db"), null, null, Map.of(), null, true, Instant.EPOCH);
        assertThrows(IllegalArgumentException.class, () -> provider.descriptor(invalid));
    }
}
