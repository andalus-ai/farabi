package tech.kayys.farabi.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.QueryContracts.Execution;
import tech.kayys.farabi.engine.QueryContracts.Status;

class JdbcExecutionRepositoryTest {
    private DataSource dataSource;
    private JdbcExecutionRepository repository;

    private static final Instant CREATED = Instant.parse("2026-11-01T00:00:00Z");

    @BeforeEach
    void setUp() throws Exception {
        String url = "jdbc:h2:mem:executions_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        dataSource = new DriverDataSource(url);
        repository = new JdbcExecutionRepository(dataSource);

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE farabi_query_executions (
                        id UUID PRIMARY KEY,
                        workspace_id VARCHAR(200) NOT NULL,
                        subject_id VARCHAR(200) NOT NULL,
                        source_id VARCHAR(200) NOT NULL,
                        provider_job_id VARCHAR(500),
                        status VARCHAR(20) NOT NULL,
                        created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                        updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                    )
                    """);
        }
    }

    @Test
    void createsAndFindsExecutionOnlyWithinItsWorkspace() {
        Execution execution = execution(UUID.randomUUID(), "workspace-a", null, Status.PENDING);
        repository.create(execution);

        assertEquals(execution, repository.find(execution.id(), "workspace-a").orElseThrow());
        assertTrue(repository.find(execution.id(), "workspace-b").isEmpty());
        assertTrue(repository.find(UUID.randomUUID(), "workspace-a").isEmpty());
    }

    @Test
    void transitionsUseExpectedStatusAndPersistProviderJobId() {
        Execution execution = execution(UUID.randomUUID(), "workspace-a", null, Status.PENDING);
        repository.create(execution);

        assertFalse(repository.transition(execution.id(), "workspace-a",
                Status.SUBMITTED, Status.RUNNING, "provider-1"));
        assertTrue(repository.transition(execution.id(), "workspace-a",
                Status.PENDING, Status.SUBMITTED, "provider-1"));

        Execution persisted = repository.find(execution.id(), "workspace-a").orElseThrow();
        assertEquals(Status.SUBMITTED, persisted.status());
        assertEquals("provider-1", persisted.providerJobId());
        assertFalse(repository.transition(execution.id(), "workspace-a",
                Status.PENDING, Status.SUBMITTED, "provider-2"));
        assertEquals("provider-1",
                repository.find(execution.id(), "workspace-a").orElseThrow().providerJobId());
    }

    @Test
    void transitionCannotCrossWorkspaceBoundary() {
        Execution execution = execution(UUID.randomUUID(), "workspace-a", null, Status.PENDING);
        repository.create(execution);

        assertFalse(repository.transition(execution.id(), "workspace-b",
                Status.PENDING, Status.SUBMITTED, "provider-1"));
        assertEquals(Status.PENDING,
                repository.find(execution.id(), "workspace-a").orElseThrow().status());
    }

    @Test
    void rejectsIllegalTerminalTransitionsBeforeTouchingDatabase() {
        Execution execution = execution(UUID.randomUUID(), "workspace-a", "provider-1", Status.SUCCEEDED);
        repository.create(execution);

        assertThrows(IllegalArgumentException.class, () -> repository.transition(
                execution.id(), "workspace-a", Status.SUCCEEDED, Status.RUNNING, null));
        assertEquals(Status.SUCCEEDED,
                repository.find(execution.id(), "workspace-a").orElseThrow().status());
    }

    private Execution execution(UUID id, String workspace, String providerJobId, Status status) {
        return new Execution(id, workspace, "subject-1", "warehouse-1",
                providerJobId, status, CREATED, CREATED);
    }

    private record DriverDataSource(String url) implements DataSource {
        @Override public Connection getConnection() throws java.sql.SQLException {
            return DriverManager.getConnection(url);
        }
        @Override public Connection getConnection(String user, String password)
                throws java.sql.SQLException {
            return DriverManager.getConnection(url, user, password);
        }
        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter writer) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() {
            return java.util.logging.Logger.getGlobal();
        }
        @Override public <T> T unwrap(Class<T> type) throws java.sql.SQLException {
            throw new java.sql.SQLException("not a wrapper");
        }
        @Override public boolean isWrapperFor(Class<?> type) { return false; }
    }
}
