package tech.kayys.andalus.bi.query.jobs;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.util.Objects;

/** Append-only audit insertion. Caller must coordinate transaction with state changes. */
public final class JdbcQueryAuditStore {
    private final DataSource dataSource;
    public JdbcQueryAuditStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
    }

    public void append(QueryExecutionAudit event) {
        Objects.requireNonNull(event);
        String sql = """
            INSERT INTO bi_query_job_audit
              (event_id,job_id,workspace_id,subject_id,event_type,occurred_at)
            VALUES (?,?,?,?,?,?)
            """;
        try (var conn = dataSource.getConnection(); var stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, event.eventId());
            stmt.setObject(2, event.jobId());
            stmt.setString(3, event.workspaceId());
            stmt.setString(4, event.subjectId());
            stmt.setString(5, event.eventType());
            stmt.setObject(6, event.occurredAt().atOffset(ZoneOffset.UTC));
            stmt.executeUpdate();
        } catch (SQLException e) { throw new IllegalStateException("audit append failed", e); }
    }
}
