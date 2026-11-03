package tech.kayys.farabi.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tech.kayys.farabi.engine.QueryContracts.Status;
import tech.kayys.farabi.engine.WorkerLeases.Lease;

@Testcontainers
class JdbcWorkerLeaseRepositoryTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private DataSource dataSource;
    private JdbcWorkerLeaseRepository repository;

    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");
    private static final Duration TTL = Duration.ofSeconds(30);

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new DriverDataSource(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
        repository = new JdbcWorkerLeaseRepository(dataSource);

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS farabi_query_executions");
            statement.execute("""
                    CREATE TABLE farabi_query_executions (
                        id UUID PRIMARY KEY,
                        workspace_id VARCHAR(200) NOT NULL,
                        subject_id VARCHAR(200) NOT NULL,
                        source_id VARCHAR(200) NOT NULL,
                        provider_job_id VARCHAR(500),
                        status VARCHAR(20) NOT NULL,
                        created_at TIMESTAMPTZ NOT NULL,
                        updated_at TIMESTAMPTZ NOT NULL,
                        lease_owner VARCHAR(200),
                        lease_fence BIGINT NOT NULL DEFAULT 0 CHECK (lease_fence >= 0),
                        lease_expires_at TIMESTAMPTZ
                    )
                    """);
        }
    }

    @Test
    void expiredLeaseCanBeReclaimedAndFencesOutThePreviousWorker() throws Exception {
        UUID executionId = insertExecution("workspace-a", Status.SUBMITTED);

        Lease first = repository.claim(executionId, "workspace-a", "worker-a", NOW, TTL)
                .orElseThrow();
        assertEquals(1L, first.fence());

        assertTrue(repository.claim(executionId, "workspace-a", "worker-b",
                NOW.plusSeconds(29), TTL).isEmpty());

        Lease second = repository.claim(executionId, "workspace-a", "worker-b",
                NOW.plusSeconds(30), TTL).orElseThrow();
        assertEquals(2L, second.fence());

        assertFalse(repository.renew(first, NOW.plusSeconds(31), TTL),
                "expired worker must not renew after another worker reclaims the row");
        assertFalse(repository.complete(first, Status.SUBMITTED, Status.RUNNING,
                NOW.plusSeconds(31)), "stale worker must not complete a reclaimed lease");

        assertTrue(repository.complete(second, Status.SUBMITTED, Status.SUCCEEDED,
                NOW.plusSeconds(32)));
        assertFalse(repository.complete(second, Status.SUBMITTED, Status.RUNNING,
                NOW.plusSeconds(33)));

        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT status, lease_owner, lease_fence, lease_expires_at "
                             + "FROM farabi_query_executions WHERE id=?")) {
            statement.setObject(1, executionId);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(Status.SUCCEEDED.name(), result.getString("status"));
                assertNull(result.getString("lease_owner"));
                assertEquals(2L, result.getLong("lease_fence"));
                assertNull(result.getTimestamp("lease_expires_at"));
            }
        }
    }

    @Test
    void claimIsWorkspaceScopedAndOnlyTargetsActiveExecutions() throws Exception {
        UUID executionId = insertExecution("workspace-a", Status.RUNNING);

        assertTrue(repository.claim(executionId, "workspace-b", "worker-x", NOW, TTL).isEmpty());
        assertTrue(repository.claim(UUID.randomUUID(), "workspace-a", "worker-x", NOW, TTL).isEmpty());

        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "UPDATE farabi_query_executions SET status=? WHERE id=?")) {
            statement.setString(1, Status.CANCELLED.name());
            statement.setObject(2, executionId);
            assertEquals(1, statement.executeUpdate());
        }

        assertTrue(repository.claim(executionId, "workspace-a", "worker-x",
                NOW.plusSeconds(60), TTL).isEmpty());
    }

    private UUID insertExecution(String workspace, Status status) throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO farabi_query_executions
                         (id, workspace_id, subject_id, source_id, provider_job_id,
                          status, created_at, updated_at)
                     VALUES (?, ?, 'subject-1', 'warehouse-1', 'provider-1', ?, ?, ?)
                     """)) {
            statement.setObject(1, id);
            statement.setString(2, workspace);
            statement.setString(3, status.name());
            statement.setObject(4, java.time.OffsetDateTime.ofInstant(NOW, java.time.ZoneOffset.UTC));
            statement.setObject(5, java.time.OffsetDateTime.ofInstant(NOW, java.time.ZoneOffset.UTC));
            assertEquals(1, statement.executeUpdate());
        }
        return id;
    }

    private record DriverDataSource(String url, String username, String password)
            implements DataSource {
        @Override public Connection getConnection() throws java.sql.SQLException {
            return DriverManager.getConnection(url, username, password);
        }
        @Override public Connection getConnection(String user, String pass)
                throws java.sql.SQLException {
            return DriverManager.getConnection(url, user, pass);
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
