package tech.kayys.andalus.bi.query.jobs;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Bounded discovery of eligible jobs. Discovery does not acquire a lease.
 * Workers must atomically claim each returned ID before acting.
 */
public final class JdbcRecoverableJobScanner {
    private final DataSource dataSource;
    private final Clock clock;

    public JdbcRecoverableJobScanner(DataSource dataSource, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.clock = Objects.requireNonNull(clock);
    }

    public List<UUID> scan(int limit) {
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("limit must be 1..500");
        String sql = """
            SELECT id FROM bi_query_jobs
            WHERE status IN ('PENDING','SUBMITTED','RUNNING')
              AND (lease_until IS NULL OR lease_until <= ?)
            ORDER BY updated_at, id
            LIMIT ?
            """;
        var result = new ArrayList<UUID>();
        try (var connection = dataSource.getConnection(); var stmt = connection.prepareStatement(sql)) {
            stmt.setObject(1, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
            stmt.setInt(2, limit);
            try (var rows = stmt.executeQuery()) {
                while (rows.next()) result.add(rows.getObject(1, UUID.class));
            }
            return List.copyOf(result);
        } catch (SQLException e) { throw new IllegalStateException("job discovery failed", e); }
    }
}
