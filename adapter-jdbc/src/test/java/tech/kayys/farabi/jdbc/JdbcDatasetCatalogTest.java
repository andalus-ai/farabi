package tech.kayys.farabi.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.DatasetContracts.*;

class JdbcDatasetCatalogTest {
    private DataSource dataSource;
    private JdbcDatasetCatalog catalog;

    @BeforeEach
    void setUp() throws Exception {
        String url = "jdbc:h2:mem:farabi_" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        dataSource = new DriverDataSource(url);
        catalog = new JdbcDatasetCatalog(dataSource);
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE farabi_datasets (id UUID PRIMARY KEY, workspace_id VARCHAR(200) NOT NULL, name VARCHAR(300) NOT NULL, source_id VARCHAR(200) NOT NULL, format VARCHAR(40) NOT NULL, location_ref VARCHAR(2000) NOT NULL, lifecycle VARCHAR(40) NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL)");
            s.execute("CREATE UNIQUE INDEX uq_farabi_dataset_workspace_name ON farabi_datasets(workspace_id, LOWER(name))");
            s.execute("CREATE TABLE farabi_dataset_profiles (dataset_id UUID NOT NULL, workspace_id VARCHAR(200) NOT NULL, row_count BIGINT NOT NULL, profiled_at TIMESTAMP WITH TIME ZONE NOT NULL, profiler_version VARCHAR(100) NOT NULL, PRIMARY KEY(dataset_id,workspace_id), FOREIGN KEY(dataset_id) REFERENCES farabi_datasets(id))");
            s.execute("CREATE TABLE farabi_dataset_profile_columns (dataset_id UUID NOT NULL, workspace_id VARCHAR(200) NOT NULL, ordinal INTEGER NOT NULL, name VARCHAR(300) NOT NULL, logical_type VARCHAR(40) NOT NULL, nullable BOOLEAN NOT NULL, null_count BIGINT NOT NULL, distinct_count BIGINT NOT NULL, PRIMARY KEY(dataset_id,workspace_id,ordinal), FOREIGN KEY(dataset_id,workspace_id) REFERENCES farabi_dataset_profiles(dataset_id,workspace_id))");
        }
    }

    @Test
    void persistsAndScopesDatasetsAndRejectsCaseInsensitiveDuplicates() {
        Dataset dataset = dataset("Sales");
        assertTrue(catalog.create(dataset));
        assertEquals(dataset, catalog.find(dataset.id(), "workspace-a").orElseThrow());
        assertTrue(catalog.find(dataset.id(), "workspace-b").isEmpty());
        assertFalse(catalog.create(dataset("sales")));
        assertTrue(catalog.create(dataset("Sales", "workspace-b")));
    }

    @Test
    void savesAndReplacesProfileAndScopesItToWorkspace() {
        Dataset dataset = dataset("Orders");
        assertTrue(catalog.create(dataset));
        Profile first = new Profile(dataset.id(), "workspace-a", 100,
                List.of(new Column("amount", LogicalType.DECIMAL, true, 2, 80)),
                Instant.parse("2026-11-01T00:00:00Z"), "profile-1");
        catalog.saveProfile(first);
        assertEquals(first, catalog.findProfile(dataset.id(), "workspace-a").orElseThrow());
        assertTrue(catalog.findProfile(dataset.id(), "workspace-b").isEmpty());

        Profile replacement = new Profile(dataset.id(), "workspace-a", 120,
                List.of(new Column("amount", LogicalType.DECIMAL, false, 0, 90),
                        new Column("region", LogicalType.STRING, true, 1, 5)),
                Instant.parse("2026-11-01T01:00:00Z"), "profile-2");
        catalog.saveProfile(replacement);
        assertEquals(replacement, catalog.findProfile(dataset.id(), "workspace-a").orElseThrow());
    }

    @Test
    void refusesProfileForDatasetOutsideWorkspace() {
        Dataset dataset = dataset("Private");
        assertTrue(catalog.create(dataset));
        Profile forged = new Profile(dataset.id(), "workspace-b", 1, List.of(),
                Instant.parse("2026-11-01T00:00:00Z"), "profile-1");
        assertThrows(IllegalArgumentException.class, () -> catalog.saveProfile(forged));
        assertTrue(catalog.findProfile(dataset.id(), "workspace-a").isEmpty());
    }

    private Dataset dataset(String name) { return dataset(name, "workspace-a"); }
    private Dataset dataset(String name, String workspace) {
        Instant now = Instant.parse("2026-11-01T00:00:00Z");
        return new Dataset(UUID.randomUUID(), workspace, name, "warehouse", Format.PARQUET,
                "catalog." + name.toLowerCase(), Lifecycle.REGISTERED, now, now);
    }

    private record DriverDataSource(String url) implements DataSource {
        @Override public Connection getConnection() throws java.sql.SQLException { return DriverManager.getConnection(url); }
        @Override public Connection getConnection(String user, String password) throws java.sql.SQLException { return DriverManager.getConnection(url, user, password); }
        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getGlobal(); }
        @Override public <T> T unwrap(Class<T> iface) throws java.sql.SQLException { throw new java.sql.SQLException("not a wrapper"); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
