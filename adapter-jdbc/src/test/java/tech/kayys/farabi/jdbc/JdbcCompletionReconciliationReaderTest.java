package tech.kayys.farabi.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tech.kayys.farabi.engine.CompletionReconciliation;
import tech.kayys.farabi.engine.CompletionReconciliation.*;
import tech.kayys.farabi.engine.QueryContracts.Status;

@Testcontainers
class JdbcCompletionReconciliationReaderTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private DataSource dataSource;
    private JdbcCompletionReconciliationReader reader;

    private static final Instant NOW = Instant.parse("2026-11-02T12:00:00Z");
    private static final UUID ID =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new DriverDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        reader = new JdbcCompletionReconciliationReader(dataSource);
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
    void readsLifecycleAndLeaseMetadataFromOneWorkspaceScopedRow() throws Exception {
        insert("workspace-a", Status.RUNNING, "worker-a", 7, NOW.plusSeconds(30));

        ReadResult result = reader.read(ID, "workspace-a");

        assertEquals(ReadState.AVAILABLE, result.state());
        Snapshot snapshot = result.snapshot();
        assertEquals(ID, snapshot.executionId());
        assertEquals("workspace-a", snapshot.workspaceId());
        assertEquals(Status.RUNNING, snapshot.status());
        assertEquals("worker-a", snapshot.leaseOwner());
        assertEquals(7L, snapshot.leaseFence());
        assertEquals(NOW.plusSeconds(30), snapshot.leaseExpiresAt());
    }

    @Test
    void observesCommittedCompletionWithClearedLease() throws Exception {
        insert("workspace-a", Status.SUCCEEDED, null, 7, null);

        Snapshot snapshot = reader.read(ID, "workspace-a").snapshot();

        assertEquals(Status.SUCCEEDED, snapshot.status());
        assertNull(snapshot.leaseOwner());
        assertNull(snapshot.leaseExpiresAt());
        assertEquals(7L, snapshot.leaseFence());
    }

    @Test
    void missingExecutionAndWorkspaceMismatchAreIndistinguishable() throws Exception {
        insert("workspace-a", Status.RUNNING, "worker-a", 7, NOW.plusSeconds(30));

        assertEquals(ReadState.MISSING, reader.read(ID, "workspace-b").state());
        assertEquals(ReadState.MISSING, reader.read(UUID.randomUUID(), "workspace-a").state());
    }

    @Test
    void databaseReadFailureIsUnresolvedRatherThanMissing() {
        DataSource unavailable = new UnavailableDataSource();
        JdbcCompletionReconciliationReader failingReader =
                new JdbcCompletionReconciliationReader(unavailable);

        assertEquals(ReadState.UNAVAILABLE, failingReader.read(ID, "workspace-a").state());
    }

    @Test
    void returnedObservationClassifiesAgainstAttemptAndExactExpiry() throws Exception {
        insert("workspace-a", Status.RUNNING, "worker-a", 7, NOW.plusSeconds(30));
        Attempt attempt = new Attempt(
                ID, "workspace-a", "worker-a", 7, Status.RUNNING, Status.SUCCEEDED);

        assertEquals(Outcome.NOT_APPLIED,
                CompletionReconciliation.classify(attempt, reader.read(ID, "workspace-a"), NOW));
        assertEquals(Outcome.UNRESOLVED,
                CompletionReconciliation.classify(
                        attempt, reader.read(ID, "workspace-a"), NOW.plusSeconds(30)));
    }

    private void insert(String workspace, Status status, String owner, long fence, Instant expiry)
            throws Exception {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO farabi_query_executions
                         (id, workspace_id, subject_id, source_id, provider_job_id,
                          status, created_at, updated_at, lease_owner, lease_fence, lease_expires_at)
                     VALUES (?, ?, 'subject-1', 'warehouse-1', 'provider-1', ?, ?, ?, ?, ?, ?)
                     """)) {
            statement.setObject(1, ID);
            statement.setString(2, workspace);
            statement.setString(3, status.name());
            statement.setObject(4, java.time.OffsetDateTime.ofInstant(NOW, java.time.ZoneOffset.UTC));
            statement.setObject(5, java.time.OffsetDateTime.ofInstant(NOW, java.time.ZoneOffset.UTC));
            statement.setString(6, owner);
            statement.setLong(7, fence);
            statement.setObject(8, expiry == null ? null
                    : java.time.OffsetDateTime.ofInstant(expiry, java.time.ZoneOffset.UTC));
            assertEquals(1, statement.executeUpdate());
        }
    }

    private record DriverDataSource(String url, String username, String password)
            implements DataSource {
        @Override public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url, username, password);
        }
        @Override public Connection getConnection(String user, String pass) throws SQLException {
            return DriverManager.getConnection(url, user, pass);
        }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter writer) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() { return Logger.getGlobal(); }
        @Override public <T> T unwrap(Class<T> type) throws SQLException {
            throw new SQLException("not a wrapper");
        }
        @Override public boolean isWrapperFor(Class<?> type) { return false; }
    }

    private static final class UnavailableDataSource implements DataSource {
        @Override public Connection getConnection() throws SQLException {
            throw new SQLException("simulated database outage");
        }
        @Override public Connection getConnection(String user, String password) throws SQLException {
            throw new SQLException("simulated database outage");
        }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter writer) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() { return Logger.getGlobal(); }
        @Override public <T> T unwrap(Class<T> type) throws SQLException {
            throw new SQLException("not a wrapper");
        }
        @Override public boolean isWrapperFor(Class<?> type) { return false; }
    }
}
