package tech.kayys.farabi.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tech.kayys.farabi.engine.QueryContracts.*;
import tech.kayys.farabi.engine.QueryLifecycle.*;
import tech.kayys.farabi.engine.QueryPollingWorker;
import tech.kayys.farabi.engine.QueryPorts;
import tech.kayys.farabi.engine.WorkerCoordinator;

@Testcontainers
class JdbcBackedQueryPollingLeaseTakeoverTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private DataSource dataSource;
    private JdbcWorkerLeaseRepository leases;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new DriverDataSource(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
        leases = new JdbcWorkerLeaseRepository(dataSource);
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
    void staleRunningProviderResponseCannotRenewLeaseAfterJdbcTakeover() throws Exception {
        Instant start = Instant.parse("2026-11-01T00:00:00Z");
        Duration ttl = Duration.ofSeconds(30);
        UUID id = UUID.randomUUID();
        insertExecution(id, start);

        MutableClock clock = new MutableClock(start);
        CountDownLatch firstProviderCall = new CountDownLatch(1);
        CountDownLatch releaseFirstCall = new CountDownLatch(1);
        AtomicInteger providerCalls = new AtomicInteger();
        Provider provider = new Provider() {
            @Override
            public RemoteState status(String job) {
                if (providerCalls.incrementAndGet() == 1) {
                    firstProviderCall.countDown();
                    try {
                        if (!releaseFirstCall.await(10, TimeUnit.SECONDS)) {
                            throw new AssertionError("provider wait timed out");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    return new RemoteState(Status.RUNNING, null);
                }
                return new RemoteState(Status.SUCCEEDED, null);
            }
            @Override public ResultPage results(String job, int offset, int limit) {
                throw new AssertionError("polling must not fetch results");
            }
            @Override public void cancel(String job) {
                throw new AssertionError("polling must not cancel jobs");
            }
        };
        QueryPorts.ExecutionRepository executions = new QueryPorts.ExecutionRepository() {
            @Override public void create(Execution execution) { throw new AssertionError(); }
            @Override public Optional<Execution> find(UUID key, String workspace) {
                if (!id.equals(key) || !"workspace-a".equals(workspace)) return Optional.empty();
                try (Connection connection = dataSource.getConnection();
                     var statement = connection.prepareStatement("""
                             SELECT id, workspace_id, subject_id, source_id, provider_job_id,
                                    status, created_at, updated_at
                             FROM farabi_query_executions WHERE id=? AND workspace_id=?
                             """)) {
                    statement.setObject(1, key);
                    statement.setString(2, workspace);
                    try (var result = statement.executeQuery()) {
                        if (!result.next()) return Optional.empty();
                        return Optional.of(new Execution(
                                result.getObject("id", UUID.class),
                                result.getString("workspace_id"),
                                result.getString("subject_id"),
                                result.getString("source_id"),
                                result.getString("provider_job_id"),
                                Status.valueOf(result.getString("status")),
                                result.getTimestamp("created_at").toInstant(),
                                result.getTimestamp("updated_at").toInstant()));
                    }
                } catch (Exception e) {
                    throw new IllegalStateException("execution lookup failed", e);
                }
            }
            @Override public boolean transition(UUID key, String workspace, Status from,
                    Status to, String providerJobId) {
                throw new AssertionError("poller must use JDBC fenced lease completion");
            }
        };
        QueryPollingWorker worker = new QueryPollingWorker(
                new WorkerCoordinator(leases, clock), executions, provider);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> staleWorker = pool.submit(
                    () -> worker.poll(id, "workspace-a", "worker-a", ttl));
            assertTrue(firstProviderCall.await(10, TimeUnit.SECONDS));

            clock.advance(ttl.plusSeconds(1));
            assertTrue(worker.poll(id, "workspace-a", "worker-b", ttl),
                    "replacement worker should claim and complete the execution");
            assertEquals(Status.SUCCEEDED, readStatus(id));
            assertEquals(2L, readFence(id));

            releaseFirstCall.countDown();
            assertFalse(staleWorker.get(10, TimeUnit.SECONDS),
                    "stale RUNNING response must fail to renew the replaced lease");
            assertEquals(Status.SUCCEEDED, readStatus(id));
            assertEquals(2L, readFence(id));
            assertEquals("worker-b", readLeaseOwner(id));
        } finally {
            releaseFirstCall.countDown();
            pool.shutdownNow();
        }
    }

    private void insertExecution(UUID id, Instant now) throws Exception {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO farabi_query_executions
                         (id, workspace_id, subject_id, source_id, provider_job_id,
                          status, created_at, updated_at)
                     VALUES (?, 'workspace-a', 'analyst', 'toba', 'provider-job-1',
                             'RUNNING', ?, ?)
                     """)) {
            statement.setObject(1, id);
            statement.setObject(2, java.time.OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
            statement.setObject(3, java.time.OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
            assertEquals(1, statement.executeUpdate());
        }
    }

    private Status readStatus(UUID id) throws Exception {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT status FROM farabi_query_executions WHERE id=?")) {
            statement.setObject(1, id);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                return Status.valueOf(result.getString(1));
            }
        }
    }

    private long readFence(UUID id) throws Exception {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT lease_fence FROM farabi_query_executions WHERE id=?")) {
            statement.setObject(1, id);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getLong(1);
            }
        }
    }

    private String readLeaseOwner(UUID id) throws Exception {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT lease_owner FROM farabi_query_executions WHERE id=?")) {
            statement.setObject(1, id);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
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

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;
        MutableClock(Instant initial) { now = new AtomicReference<>(initial); }
        void advance(Duration duration) { now.updateAndGet(value -> value.plus(duration)); }
        @Override public Instant instant() { return now.get(); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
