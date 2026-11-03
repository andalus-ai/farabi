package tech.kayys.farabi.jdbc.datasource;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tech.kayys.farabi.engine.DataSourceContracts.DataSourceDescriptor;
import tech.kayys.farabi.engine.DataSourceContracts.ProviderKind;
import tech.kayys.farabi.engine.DataSourceContracts.QueryRequest;

@Testcontainers(disabledWithoutDocker = true)
class JdbcDatabaseCompatibilityIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Test
    void supportsPostgresqlMetadataCapabilitiesAndBoundQueries() throws Exception {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        assertConnector(source, "postgres");
    }

    @Test
    void supportsMysqlMetadataCapabilitiesAndBoundQueries() throws Exception {
        MysqlDataSource source = new MysqlDataSource();
        source.setURL(MYSQL.getJdbcUrl());
        source.setUser(MYSQL.getUsername());
        source.setPassword(MYSQL.getPassword());
        assertConnector(source, "mysql");
    }

    private void assertConnector(DataSource source, String sourceId) throws Exception {
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE fact_sales (id INTEGER PRIMARY KEY, amount DECIMAL(12,2) NOT NULL)");
            statement.executeUpdate("INSERT INTO fact_sales (id, amount) VALUES (1, 12.50), (2, 22.00)");
        }

        var descriptor = new DataSourceDescriptor(sourceId, sourceId, ProviderKind.JDBC, true);
        var registry = new JdbcDataSourceRegistry(Map.of(
                sourceId, new JdbcDataSourceRegistry.Entry(descriptor, source)));
        var catalog = new JdbcCatalogProvider(registry);
        var columns = catalog.listColumns(sourceId, null, null, "fact_sales");
        assertEquals(2, columns.size());

        var capabilities = new JdbcCapabilitiesProvider(registry).capabilities(sourceId);
        assertFalse(capabilities.productName().isBlank());
        assertFalse(capabilities.driverName().isBlank());

        var result = new JdbcQueryExecutor(registry).execute(new QueryRequest(
                sourceId, "SELECT id, amount FROM fact_sales WHERE amount > ? ORDER BY id",
                List.of(new BigDecimal("10.00")), 10));
        assertEquals(2, result.rows().size());
        assertFalse(result.truncated());
    }
}
