package tech.kayys.andalus.bi.query.jobs;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class JdbcQueryJobStore implements QueryJobStore {
    private final DataSource dataSource;
    public JdbcQueryJobStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
    }

    @Override public void create(QueryJob job) {
        Objects.requireNonNull(job);
        String sql = "INSERT INTO bi_query_jobs(id,workspace_id,subject_id,source_id,provider_job_id,status,created_at,updated_at,expires_at) VALUES (?,?,?,?,?,?,?,?,?)";
        try (var connection = dataSource.getConnection(); var stmt = connection.prepareStatement(sql)) {
            stmt.setObject(1, job.id());
            stmt.setString(2, job.workspaceId());
            stmt.setString(3, job.subjectId());
            stmt.setString(4, job.sourceId());
            stmt.setString(5, job.providerJobId());
            stmt.setString(6, job.status().name());
            stmt.setObject(7, utc(job.createdAt()));
            stmt.setObject(8, utc(job.updatedAt()));
            stmt.setObject(9, utc(job.expiresAt()));
            stmt.executeUpdate();
        } catch (SQLException e) { throw new IllegalStateException("query job create failed", e); }
    }

    @Override public Optional<QueryJob> find(UUID id) {
        Objects.requireNonNull(id);
        String sql = "SELECT workspace_id,subject_id,source_id,provider_job_id,status,created_at,updated_at,expires_at FROM bi_query_jobs WHERE id=?";
        try (var connection = dataSource.getConnection(); var stmt = connection.prepareStatement(sql)) {
            stmt.setObject(1, id);
            try (var rows = stmt.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                return Optional.of(new QueryJob(id, rows.getString(1), rows.getString(2), rows.getString(3),
                    rows.getString(4), QueryJob.Status.valueOf(rows.getString(5)),
                    rows.getObject(6, OffsetDateTime.class).toInstant(),
                    rows.getObject(7, OffsetDateTime.class).toInstant(),
                    rows.getObject(8, OffsetDateTime.class).toInstant()));
            }
        } catch (SQLException e) { throw new IllegalStateException("query job read failed", e); }
    }

    @Override public boolean transition(UUID id, QueryJob.Status expected, QueryJob.Status next,
                                        String providerJobId, Instant now) {
        Objects.requireNonNull(id);
        Objects.requireNonNull(expected);
        Objects.requireNonNull(next);
        Objects.requireNonNull(now);
        String sql = "UPDATE bi_query_jobs SET status=?, provider_job_id=COALESCE(?,provider_job_id), updated_at=? WHERE id=? AND status=? AND expires_at>?";
        try (var connection = dataSource.getConnection(); var stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, next.name());
            stmt.setString(2, providerJobId);
            stmt.setObject(3, utc(now));
            stmt.setObject(4, id);
            stmt.setString(5, expected.name());
            stmt.setObject(6, utc(now));
            return stmt.executeUpdate() == 1;
        } catch (SQLException e) { throw new IllegalStateException("query job transition failed", e); }
    }

    private static OffsetDateTime utc(Instant value) { return value.atOffset(ZoneOffset.UTC); }
}
