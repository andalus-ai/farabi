package tech.kayys.andalus.bi.query.jobs;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

/**
 * PostgreSQL atomic worker lease. The lease owner is a trusted backend worker
 * identifier, never a user-supplied value. A lease is not a fencing token:
 * workers must recheck ownership before committing side effects.
 */
public final class JdbcQueryJobLeaseStore {
    private final DataSource dataSource;
    private final Clock clock;

    public JdbcQueryJobLeaseStore(DataSource dataSource, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.clock = Objects.requireNonNull(clock);
    }

    public boolean claim(UUID jobId, String workerId, Duration ttl) {
        Objects.requireNonNull(jobId);
        validate(workerId, ttl);
        String sql = """
            UPDATE bi_query_jobs SET lease_owner=?, lease_until=?,
              attempt_count=attempt_count+1
            WHERE id=? AND status IN ('PENDING','SUBMITTED','RUNNING')
              AND expires_at>?
              AND (lease_until IS NULL OR lease_until<=?)
            """;
        var now = clock.instant();
        try (var conn = dataSource.getConnection(); var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, workerId);
            stmt.setObject(2, now.plus(ttl).atOffset(ZoneOffset.UTC));
            stmt.setObject(3, jobId);
            stmt.setObject(4, now.atOffset(ZoneOffset.UTC));
            stmt.setObject(5, now.atOffset(ZoneOffset.UTC));
            return stmt.executeUpdate() == 1;
        } catch (SQLException e) { throw new IllegalStateException("job lease claim failed", e); }
    }

    public boolean renew(UUID jobId, String workerId, Duration ttl) {
        Objects.requireNonNull(jobId);
        validate(workerId, ttl);
        var now = clock.instant();
        String sql = """
            UPDATE bi_query_jobs SET lease_until=?
            WHERE id=? AND lease_owner=? AND lease_until>?
              AND expires_at>? AND status IN ('PENDING','SUBMITTED','RUNNING')
            """;
        try (var conn = dataSource.getConnection(); var stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, now.plus(ttl).atOffset(ZoneOffset.UTC));
            stmt.setObject(2, jobId);
            stmt.setString(3, workerId);
            stmt.setObject(4, now.atOffset(ZoneOffset.UTC));
            stmt.setObject(5, now.atOffset(ZoneOffset.UTC));
            return stmt.executeUpdate() == 1;
        } catch (SQLException e) { throw new IllegalStateException("job lease renewal failed", e); }
    }

    public boolean release(UUID jobId, String workerId) {
        Objects.requireNonNull(jobId);
        if (workerId == null || workerId.isBlank()) throw new IllegalArgumentException("workerId required");
        String sql = """
            UPDATE bi_query_jobs SET lease_owner=NULL, lease_until=NULL
            WHERE id=? AND lease_owner=?
            """;
        try (var conn = dataSource.getConnection(); var stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, jobId);
            stmt.setString(2, workerId);
            return stmt.executeUpdate() == 1;
        } catch (SQLException e) { throw new IllegalStateException("job lease release failed", e); }
    }

    private static void validate(String workerId, Duration ttl) {
        if (workerId == null || workerId.isBlank() || workerId.length() > 128)
            throw new IllegalArgumentException("invalid worker ID");
        if (ttl == null || ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofMinutes(10)) > 0)
            throw new IllegalArgumentException("lease TTL must be positive and <= 10 minutes");
    }
}
