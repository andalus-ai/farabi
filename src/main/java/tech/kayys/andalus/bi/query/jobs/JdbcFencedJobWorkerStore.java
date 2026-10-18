package tech.kayys.andalus.bi.query.jobs;

import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.Objects;
import java.util.UUID;

/**
 * Database-enforced fencing for worker-owned job transitions.
 * A claim increments a monotonically increasing lease epoch. Stale workers
 * cannot commit state transitions or audit rows using an older epoch.
 */
public final class JdbcFencedJobWorkerStore {
    public record Lease(UUID jobId, String workerId, long epoch, Instant until) {}
    private final DataSource source;
    private final Clock clock;

    public JdbcFencedJobWorkerStore(DataSource source, Clock clock) {
        this.source = Objects.requireNonNull(source);
        this.clock = Objects.requireNonNull(clock);
    }

    public Lease claim(UUID jobId, String workerId, Duration ttl) {
        Objects.requireNonNull(jobId);
        validate(workerId, ttl);
        Instant now = clock.instant();
        String sql = """
            UPDATE bi_query_jobs
            SET lease_owner=?, lease_until=?, lease_epoch=lease_epoch+1,
                attempt_count=attempt_count+1
            WHERE id=? AND status IN ('PENDING','SUBMITTED','RUNNING')
              AND expires_at>?
              AND (lease_until IS NULL OR lease_until<=?)
            RETURNING lease_epoch
            """;
        try (Connection c = source.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, workerId);
            s.setObject(2, utc(now.plus(ttl)));
            s.setObject(3, jobId);
            s.setObject(4, utc(now));
            s.setObject(5, utc(now));
            try (ResultSet rows = s.executeQuery()) {
                return rows.next() ? new Lease(jobId, workerId, rows.getLong(1), now.plus(ttl)) : null;
            }
        } catch (SQLException e) { throw new IllegalStateException("fenced lease claim failed", e); }
    }

    /**
     * Atomic status transition and audit append. Both roll back on failure.
     * Does not fence remote Dremio side effects; use idempotency/reconciliation
     * for those independently.
     */
    public boolean transition(Lease lease, QueryJob.Status expected, QueryJob.Status next,
                              QueryExecutionAudit audit) {
        Objects.requireNonNull(lease);
        Objects.requireNonNull(expected);
        Objects.requireNonNull(next);
        Objects.requireNonNull(audit);
        if (!audit.jobId().equals(lease.jobId())) throw new IllegalArgumentException("audit job mismatch");
        if (!allowed(expected, next)) throw new IllegalArgumentException("illegal job transition");
        Instant now = clock.instant();
        String update = """
            UPDATE bi_query_jobs SET status=?, updated_at=?
            WHERE id=? AND status=? AND lease_owner=? AND lease_epoch=?
              AND lease_until>? AND expires_at>?
            """;
        String append = """
            INSERT INTO bi_query_job_audit
              (event_id,job_id,workspace_id,subject_id,event_type,occurred_at)
            SELECT ?,id,workspace_id,subject_id,?,?
            FROM bi_query_jobs WHERE id=? AND lease_owner=? AND lease_epoch=?
            """;
        try (Connection c = source.getConnection()) {
            c.setAutoCommit(false);
            try {
                int changed;
                try (PreparedStatement s = c.prepareStatement(update)) {
                    s.setString(1, next.name());
                    s.setObject(2, utc(now));
                    s.setObject(3, lease.jobId());
                    s.setString(4, expected.name());
                    s.setString(5, lease.workerId());
                    s.setLong(6, lease.epoch());
                    s.setObject(7, utc(now));
                    s.setObject(8, utc(now));
                    changed = s.executeUpdate();
                }
                if (changed != 1) { c.rollback(); return false; }
                try (PreparedStatement s = c.prepareStatement(append)) {
                    s.setObject(1, audit.eventId());
                    s.setString(2, audit.eventType());
                    s.setObject(3, utc(now));
                    s.setObject(4, lease.jobId());
                    s.setString(5, lease.workerId());
                    s.setLong(6, lease.epoch());
                    if (s.executeUpdate() != 1) throw new SQLException("audit insert did not match job");
                }
                c.commit();
                return true;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) { throw new IllegalStateException("fenced transition failed", e); }
    }

    private static boolean allowed(QueryJob.Status from, QueryJob.Status to) {
        return switch (from) {
            case PENDING -> to == QueryJob.Status.SUBMITTED || to == QueryJob.Status.FAILED ||
                            to == QueryJob.Status.CANCELLED;
            case SUBMITTED -> to == QueryJob.Status.RUNNING || to == QueryJob.Status.FAILED ||
                              to == QueryJob.Status.CANCELLED;
            case RUNNING -> to == QueryJob.Status.SUCCEEDED || to == QueryJob.Status.FAILED ||
                            to == QueryJob.Status.CANCELLED;
            default -> false;
        };
    }

    private static void validate(String workerId, Duration ttl) {
        if (workerId == null || workerId.isBlank() || workerId.length() > 128)
            throw new IllegalArgumentException("invalid worker ID");
        if (ttl == null || ttl.isZero() || ttl.isNegative() || ttl.compareTo(Duration.ofMinutes(10)) > 0)
            throw new IllegalArgumentException("lease ttl must be positive and <= 10 minutes");
    }
    private static OffsetDateTime utc(Instant instant) { return instant.atOffset(ZoneOffset.UTC); }
}
