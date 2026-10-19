package tech.kayys.farabi.adapter.jdbc;

import tech.kayys.farabi.engine.domain.QueryExecution;
import tech.kayys.farabi.engine.port.QueryExecutionRepository;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** JDBC adapter for Farabi-owned query execution records. SQL dialect is PostgreSQL. */
public final class JdbcQueryExecutionRepository implements QueryExecutionRepository {
    private final DataSource dataSource;
    public JdbcQueryExecutionRepository(DataSource dataSource) { this.dataSource = Objects.requireNonNull(dataSource); }

    @Override public void create(QueryExecution e) {
        Objects.requireNonNull(e);
        String sql = "INSERT INTO farabi_query_executions (id, workspace_id, subject_id, source_id, provider_job_id, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection c = dataSource.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setObject(1, e.id()); p.setString(2, e.workspaceId()); p.setString(3, e.subjectId());
            p.setString(4, e.sourceId()); p.setString(5, e.providerJobId()); p.setString(6, e.status().name());
            p.setTimestamp(7, Timestamp.from(e.createdAt())); p.setTimestamp(8, Timestamp.from(e.updatedAt()));
            p.executeUpdate();
        } catch (SQLException ex) { throw new IllegalStateException("Could not persist Farabi query execution", ex); }
    }

    @Override public Optional<QueryExecution> find(UUID id) {
        Objects.requireNonNull(id);
        String sql = "SELECT id, workspace_id, subject_id, source_id, provider_job_id, status, created_at, updated_at FROM farabi_query_executions WHERE id = ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setObject(1, id);
            try (ResultSet rs = p.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new QueryExecution(rs.getObject("id", UUID.class), rs.getString("workspace_id"),
                    rs.getString("subject_id"), rs.getString("source_id"), rs.getString("provider_job_id"),
                    QueryExecution.Status.valueOf(rs.getString("status")), rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant()));
            }
        } catch (SQLException | IllegalArgumentException ex) { throw new IllegalStateException("Could not read Farabi query execution", ex); }
    }

    @Override public boolean transition(UUID id, QueryExecution.Status expected, QueryExecution.Status next, String providerJobId) {
        Objects.requireNonNull(id); Objects.requireNonNull(expected); Objects.requireNonNull(next);
        if (!allowed(expected, next)) throw new IllegalArgumentException("Illegal query status transition: " + expected + " -> " + next);
        if (providerJobId != null && providerJobId.isBlank()) throw new IllegalArgumentException("providerJobId must be null or non-blank");
        String sql = "UPDATE farabi_query_executions SET status = ?, provider_job_id = COALESCE(?, provider_job_id), updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status = ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, next.name()); p.setString(2, providerJobId); p.setObject(3, id); p.setString(4, expected.name());
            return p.executeUpdate() == 1;
        } catch (SQLException ex) { throw new IllegalStateException("Could not transition Farabi query execution", ex); }
    }

    private static boolean allowed(QueryExecution.Status from, QueryExecution.Status to) {
        return switch (from) {
            case PENDING -> to == QueryExecution.Status.SUBMITTED || to == QueryExecution.Status.FAILED || to == QueryExecution.Status.CANCELLED;
            case SUBMITTED -> to == QueryExecution.Status.RUNNING || to == QueryExecution.Status.FAILED || to == QueryExecution.Status.CANCELLED;
            case RUNNING -> to == QueryExecution.Status.SUCCEEDED || to == QueryExecution.Status.FAILED || to == QueryExecution.Status.CANCELLED;
            case SUCCEEDED, FAILED, CANCELLED -> false;
        };
    }
}
