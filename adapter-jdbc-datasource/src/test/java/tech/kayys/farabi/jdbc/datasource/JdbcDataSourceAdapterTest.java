package tech.kayys.farabi.jdbc.datasource;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.h2.jdbcx.JdbcDataSource;
import tech.kayys.farabi.engine.DataSourceContracts.Descriptor;
import tech.kayys.farabi.engine.QueryContracts.*;

class JdbcDataSourceAdapterTest {
    private JdbcDataSourceRegistry registry() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:farabi;DB_CLOSE_DELAY=-1");
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS sales (id INT PRIMARY KEY, amount DECIMAL(10,2))");
            statement.execute("MERGE INTO sales KEY(id) VALUES (1, 12.50), (2, 20.00)");
        }
        var descriptor = new Descriptor("sales", "jdbc", "Sales", "h2");
        return new JdbcDataSourceRegistry(Map.of("sales", new JdbcDataSourceRegistry.Entry(descriptor, source)));
    }

    @Test void discoversTablesAndColumns() throws Exception {
        var catalog = new JdbcCatalogProvider(registry());
        assertTrue(catalog.listObjects("sales", null, null).stream().anyMatch(o -> o.name().equalsIgnoreCase("SALES")));
        assertEquals(2, catalog.listColumns("sales", null, "PUBLIC", "SALES").size());
    }

    @Test void executesBoundedParameterizedReadOnlyQuery() throws Exception {
        var executor = new JdbcQueryExecutor(registry());
        var plan = new ParameterizedPlan("sales", "h2",
                "SELECT id, amount FROM sales WHERE amount > ? ORDER BY id",
                List.of(new QueryParameter("minimum", ParameterType.DECIMAL, new BigDecimal("10.00"))));
        var result = executor.execute(plan);
        assertEquals(2, result.rows().size());
        assertFalse(result.truncated());
    }

    @Test void rejectsUnknownSource() {
        var registry = new JdbcDataSourceRegistry(Map.of());
        var plan = new ParameterizedPlan("missing", "h2", "SELECT 1", List.of());
        assertThrows(IllegalArgumentException.class, () -> new JdbcQueryExecutor(registry).execute(plan));
    }
}
