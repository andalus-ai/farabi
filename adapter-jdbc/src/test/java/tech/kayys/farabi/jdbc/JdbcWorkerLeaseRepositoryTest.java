package tech.kayys.farabi.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
    void leaseIsExpiredAtTheExactExpiryInstant() throws Exception {
        UUID executionId = insertExecution("workspace-a", Status.SUBMITTED);
        Lease first = repository.claim(executionId, "workspace-a", "worker-a", NOW, TTL)
                .orElseThrow();
        Instant expiresAt = NOW.plus(TTL);

        assertFalse(repository.renew(first, expiresAt, TTL),
                "renewal must reject a lease at its exclusive expiry boundary");
        assertFalse(repository.complete(first, Status.SUBMITTED, Status.RUNNING, expiresAt),
                "completion must reject a lease at its exclusive expiry boundary");

        Lease replacement = repository.claim(executionId, "workspace-a", "worker-b",
                expiresAt, TTL).orElseThrow();
        assertEquals(2L, replacement.fence());
        assertFalse(repository.complete(first, Status.SUBMITTED, Status.RUNNING,
                expiresAt.plusMillis(1)),
                "the expired worker must remain fenced out after reclamation");
        assertTrue(repository.complete(replacement, Status.SUBMITTED, Status.RUNNING,
                expiresAt.plusMillis(1)));
    }

    @Test
    void concurrentWorkersCannotClaimTheSameExecution() throws Exception {
        UUID executionId = insertExecution("workspace-a", Status.SUBMITTED);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);

        try {
            var first = workers.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return repository.claim(executionId, "workspace-a", "worker-a", NOW, TTL);
            });
            var second = workers.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return repository.claim(executionId, "workspace-a", "worker-b", NOW, TTL);
            });

            assertTrue(ready.await(10, TimeUnit.SECONDS), "both workers should be ready");
            start.countDown();

            var firstClaim = first.get(10, TimeUnit.SECONDS);
            var secondClaim = second.get(10, TimeUnit.SECONDS);
            assertNotEquals(firstClaim.isPresent(), secondClaim.isPresent(),
                    "exactly one concurrent claimant must acquire the lease");

            Lease winner = firstClaim.orElseGet(secondClaim::orElseThrow);
            assertEquals(1L, winner.fence(),
                    "a losing concurrent claim must not increment the fencing token");
            assertTrue(winner.workerId().equals("worker-a")
                    || winner.workerId().equals("worker-b"));
        } finally {
            workers.shutdownNow();
        }

        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT lease_owner, lease_fence FROM farabi_query_executions WHERE id=?")) {
            statement.setObject(1, executionId);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(1L, result.getLong("lease_fence"));
                assertNotNull(result.getString("lease_owner"));
            }
        }
    }

    @Test
    void repeatedConcurrentTakeoversHaveOneWinnerAndMonotonicFences() throws Exception {
        UUID executionId = insertExecution("workspace-a", Status.RUNNING);
        int contenders = 8;
        int takeoverRounds = 5;
        ExecutorService workers = Executors.newFixedThreadPool(contenders);
        Lease previousWinner = null;

        try {
            for (int round = 0; round < takeoverRounds; round++) {
                Instant claimTime = NOW.plus(TTL.multipliedBy(round));
                CountDownLatch ready = new CountDownLatch(contenders);
                CountDownLatch start = new CountDownLatch(1);
                List<java.util.concurrent.Future<java.util.Optional<Lease>>> attempts =
                        new ArrayList<>();

                for (int worker = 0; worker < contenders; worker++) {
                    String workerId = "round-" + round + "-worker-" + worker;
                    attempts.add(workers.submit(() -> {
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS)) {
                            throw new AssertionError("claim wave did not start");
                        }
                        return repository.claim(executionId, "workspace-a",
                                workerId, claimTime, TTL);
                    }));
                }

                assertTrue(ready.await(10, TimeUnit.SECONDS),
                        "all contenders should be ready for round " + round);
                start.countDown();

                List<Lease> winners = new ArrayList<>();
                for (var attempt : attempts) {
                    attempt.get(10, TimeUnit.SECONDS).ifPresent(winners::add);
                }

                assertEquals(1, winners.size(),
                        "exactly one worker must win takeover round " + round);
                Lease winner = winners.getFirst();
                assertEquals(round + 1L, winner.fence(),
                        "fencing token must increase exactly once per successful takeover");

                if (previousWinner != null) {
                    assertFalse(repository.renew(previousWinner, claimTime, TTL),
                            "previous owner must not renew after takeover round " + round);
                    assertFalse(repository.complete(previousWinner, Status.RUNNING,
                            Status.SUCCEEDED, claimTime),
                            "previous owner must not complete after takeover round " + round);
                }
                previousWinner = winner;

                try (Connection connection = dataSource.getConnection();
                     var statement = connection.prepareStatement(
                             "SELECT lease_owner, lease_fence FROM farabi_query_executions WHERE id=?")) {
                    statement.setObject(1, executionId);
                    try (var result = statement.executeQuery()) {
                        assertTrue(result.next());
                        assertEquals(winner.workerId(), result.getString("lease_owner"));
                        assertEquals(round + 1L, result.getLong("lease_fence"));
                    }
                }
            }
        } finally {
            workers.shutdownNow();
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
