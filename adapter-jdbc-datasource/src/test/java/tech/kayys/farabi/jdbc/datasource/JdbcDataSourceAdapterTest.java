package tech.kayys.farabi.jdbc.datasource;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.DriverManager;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.h2.jdbcx.JdbcDataSource;
import tech.kayys.farabi.engine.DataSourceContracts.*;

class JdbcDataSourceAdapterTest {
    private JdbcDataSourceRegistry registry(boolean readOnly) throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:farabi;DB_CLOSE_DELAY=-1");
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS sales (id INT PRIMARY KEY, amount DECIMAL(10,2))");
            statement.execute("MERGE INTO sales KEY(id) VALUES (1, 12.50), (2, 20.00)");
        }
        var descriptor = new DataSourceDescriptor("sales", "Sales", ProviderKind.JDBC, readOnly);
        return new JdbcDataSourceRegistry(Map.of("sales", new JdbcDataSourceRegistry.Entry(descriptor, source)));
    }

    @Test void discoversTablesAndColumns() throws Exception {
        var registry = registry(true);
        var catalog = new JdbcCatalogProvider(registry);
        assertTrue(catalog.listObjects("sales", null, null).stream().anyMatch(o -> o.name().equalsIgnoreCase("SALES")));
        assertEquals(2, catalog.listColumns("sales", null, "PUBLIC", "SALES").size());
    }

    @Test void executesBoundedParameterizedReadOnlyQuery() throws Exception {
        var executor = new JdbcQueryExecutor(registry(true));
        var result = executor.execute(new QueryRequest("sales",
                "SELECT id, amount FROM sales WHERE amount > ? ORDER BY id", java.util.List.of(10), 1));
        assertEquals(1, result.rows().size());
        assertTrue(result.truncated());
    }

    @Test void rejectsNonReadOnlyRegistration() throws Exception {
        var executor = new JdbcQueryExecutor(registry(false));
        assertThrows(SecurityException.class, () -> executor.execute(
                new QueryRequest("sales", "SELECT 1", java.util.List.of(), 10)));
    }

    @Test void rejectsUnknownSource() {
        var registry = new JdbcDataSourceRegistry(Map.of());
        assertThrows(IllegalArgumentException.class, () -> new JdbcQueryExecutor(registry)
                .execute(new QueryRequest("missing", "SELECT 1", java.util.List.of(), 10)));
    }
}
