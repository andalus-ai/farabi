package tech.kayys.farabi.quarkus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.dremio.DremioExecutor;
import tech.kayys.farabi.dremio.DremioJobProvider;
import tech.kayys.farabi.dremio.DremioSourceProvider;
import tech.kayys.farabi.engine.DataSourceContracts;
import tech.kayys.farabi.engine.DataSourceContracts.Catalog;
import tech.kayys.farabi.engine.DataSourceContracts.CatalogEntry;
import tech.kayys.farabi.engine.DataSourceContracts.ColumnMetadata;
import tech.kayys.farabi.engine.DataSourceContracts.ConnectionDefinition;
import tech.kayys.farabi.engine.DataSourceContracts.Descriptor;
import tech.kayys.farabi.engine.DataSourceContracts.EntryKind;
import tech.kayys.farabi.engine.DataSourceContracts.Health;
import tech.kayys.farabi.engine.DataSourceContracts.LogicalType;
import tech.kayys.farabi.engine.DataSourceContracts.RelationMetadata;

class DremioDataSourcePortAdapterTest {
    private static final ConnectionDefinition DEFINITION = new ConnectionDefinition(
            "source-1", "workspace-1", "Analytics lake", DataSourceContracts.ProviderKind.DREMIO,
            "dremio", URI.create("https://dremio.example.test"), null, null, Map.of(),
            null, true, Instant.parse("2026-01-01T00:00:00Z"));

    private static final CatalogEntry CATALOG = new CatalogEntry("catalog-id", "lake", EntryKind.CATALOG);
    private static final CatalogEntry SCHEMA = new CatalogEntry("schema-id", "sales", EntryKind.SCHEMA);
    private static final CatalogEntry TABLE = new CatalogEntry("table-id", "orders", EntryKind.TABLE);
    private static final List<ColumnMetadata> COLUMNS =
            List.of(new ColumnMetadata("order_id", LogicalType.INTEGER, false));

    @Test
    void mapsWorkspaceSourceNavigationAndMetadataThroughOpaqueDremioIds() {
        Catalog catalog = new Catalog() {
            @Override public List<CatalogEntry> catalogs() { return List.of(CATALOG); }
            @Override public List<CatalogEntry> schemas(String catalogId) {
                assertEquals("catalog-id", catalogId);
                return List.of(SCHEMA);
            }
            @Override public List<CatalogEntry> relations(String catalogId, String schemaId) {
                assertEquals("catalog-id", catalogId);
                assertEquals("schema-id", schemaId);
                return List.of(TABLE);
            }
            @Override public RelationMetadata describe(String relationId) {
                assertEquals("table-id", relationId);
                return new RelationMetadata(relationId, "lake", "sales", "orders",
                        EntryKind.TABLE, COLUMNS);
            }
        };
        DremioDataSourcePortAdapter adapter = adapter(catalog);

        assertEquals(List.of("lake"), adapter.catalogs(DEFINITION));
        assertEquals(List.of("sales"), adapter.schemas(DEFINITION, "lake"));
        assertEquals(List.of(TABLE), adapter.datasets(DEFINITION, "lake", "sales"));
        assertEquals(COLUMNS, adapter.columns(DEFINITION, "lake", "sales", "orders"));
        assertEquals(Health.AVAILABLE, adapter.testConnection(DEFINITION).health());
    }

    @Test
    void exposesCapabilitiesWithoutClaimingParameterBindingOrTransactions() {
        DremioDataSourcePortAdapter adapter = adapter(emptyCatalog());
        var capabilities = adapter.capabilities(DEFINITION);

        assertTrue(capabilities.catalogDiscovery());
        assertTrue(capabilities.schemaDiscovery());
        assertTrue(capabilities.tableDiscovery());
        assertTrue(capabilities.queryExecution());
        assertTrue(capabilities.asynchronousJobs());
        assertTrue(capabilities.cancellation());
        assertEquals(false, capabilities.parameterBinding());
        assertEquals(false, capabilities.transactions());
        assertEquals("dremio-sql", capabilities.dialectId());
    }

    private static DremioDataSourcePortAdapter adapter(Catalog catalog) {
        DremioSourceProvider provider = new DremioSourceProvider(definition ->
                new DremioSourceProvider.Source(
                        new Descriptor(definition.sourceId(), "dremio", definition.displayName(), "v3"),
                        catalog, mock(DremioExecutor.class), mock(DremioJobProvider.class)));
        return new DremioDataSourcePortAdapter(provider);
    }

    private static Catalog emptyCatalog() {
        return new Catalog() {
            @Override public List<CatalogEntry> catalogs() { return List.of(); }
            @Override public List<CatalogEntry> schemas(String catalog) { return List.of(); }
            @Override public List<CatalogEntry> relations(String catalog, String schema) { return List.of(); }
            @Override public RelationMetadata describe(String relationId) {
                throw new IllegalArgumentException("not found");
            }
        };
    }
}
