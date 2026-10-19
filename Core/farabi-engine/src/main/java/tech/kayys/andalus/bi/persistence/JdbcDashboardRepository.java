package tech.kayys.andalus.bi.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import tech.kayys.andalus.bi.artifact.DashboardArtifact;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL repository. Writes are atomic and use compare-and-swap versioning.
 * Each read is scoped by workspaceId to prevent cross-workspace lookups.
 * Caller must still enforce identity and workspace authorization.
 */
public final class JdbcDashboardRepository implements DashboardRepository {
    private final DataSource dataSource;
    private final ObjectMapper mapper;

    public JdbcDashboardRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Override
    public DashboardArtifact create(DashboardArtifact artifact) {
        Objects.requireNonNull(artifact, "artifact");
        if (artifact.version() != 1) throw new IllegalArgumentException("initial version must be 1");
        return transact(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO bi_dashboard_heads(workspace_id,dashboard_id,current_version) VALUES (?,?,1)")) {
                statement.setString(1, artifact.workspaceId());
                statement.setObject(2, artifact.id());
                statement.executeUpdate();
            }
            insertVersion(connection, artifact);
            return artifact;
        });
    }

    @Override
    public DashboardArtifact append(DashboardArtifact artifact, int expectedPreviousVersion) {
        Objects.requireNonNull(artifact, "artifact");
        if (expectedPreviousVersion < 1 || artifact.version() != expectedPreviousVersion + 1) {
            throw new IllegalArgumentException("artifact version must be expectedPreviousVersion + 1");
        }
        return transact(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE bi_dashboard_heads SET current_version=? WHERE workspace_id=? AND dashboard_id=? AND current_version=?")) {
                statement.setInt(1, artifact.version());
                statement.setString(2, artifact.workspaceId());
                statement.setObject(3, artifact.id());
                statement.setInt(4, expectedPreviousVersion);
                if (statement.executeUpdate() != 1) {
                    throw new DashboardConflictException("dashboard does not exist or version conflict");
                }
            }
            insertVersion(connection, artifact);
            return artifact;
        });
    }

    @Override
    public Optional<DashboardArtifact> latest(String workspaceId, UUID dashboardId) {
        return queryOne(workspaceId, dashboardId, null);
    }

    @Override
    public Optional<DashboardArtifact> version(String workspaceId, UUID dashboardId, int version) {
        if (version < 1) throw new IllegalArgumentException("version must be >= 1");
        return queryOne(workspaceId, dashboardId, version);
    }

    @Override
    public List<Integer> versions(String workspaceId, UUID dashboardId) {
        requireScope(workspaceId, dashboardId);
        String sql = "SELECT version FROM bi_dashboard_versions WHERE workspace_id=? AND dashboard_id=? ORDER BY version";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, workspaceId);
            statement.setObject(2, dashboardId);
            try (ResultSet rows = statement.executeQuery()) {
                List<Integer> result = new ArrayList<>();
                while (rows.next()) result.add(rows.getInt(1));
                return List.copyOf(result);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to list dashboard versions", e);
        }
    }

    private Optional<DashboardArtifact> queryOne(String workspaceId, UUID dashboardId, Integer version) {
        requireScope(workspaceId, dashboardId);
        String sql = version == null
                ? "SELECT v.artifact_json FROM bi_dashboard_versions v JOIN bi_dashboard_heads h ON h.workspace_id=v.workspace_id AND h.dashboard_id=v.dashboard_id AND h.current_version=v.version WHERE v.workspace_id=? AND v.dashboard_id=?"
                : "SELECT artifact_json FROM bi_dashboard_versions WHERE workspace_id=? AND dashboard_id=? AND version=?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, workspaceId);
            statement.setObject(2, dashboardId);
            if (version != null) statement.setInt(3, version);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                return Optional.of(mapper.readValue(rows.getString(1), DashboardArtifact.class));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to read dashboard", e);
        }
    }

    private void insertVersion(Connection connection, DashboardArtifact artifact) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO bi_dashboard_versions(workspace_id,dashboard_id,version,artifact_json,created_at) VALUES (?,?,?,CAST(? AS jsonb),?)")) {
            statement.setString(1, artifact.workspaceId());
            statement.setObject(2, artifact.id());
            statement.setInt(3, artifact.version());
            statement.setString(4, mapper.writeValueAsString(artifact));
            statement.setObject(5, java.time.OffsetDateTime.ofInstant(artifact.createdAt(), java.time.ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    private static void requireScope(String workspaceId, UUID dashboardId) {
        if (workspaceId == null || workspaceId.isBlank() || dashboardId == null) {
            throw new IllegalArgumentException("workspaceId and dashboardId are required");
        }
    }

    private interface Transaction<T> { T apply(Connection connection) throws Exception; }

    private <T> T transact(Transaction<T> action) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = action.apply(connection);
                connection.commit();
                return result;
            } catch (Exception | Error failure) {
                try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                if (failure instanceof RuntimeException runtime) throw runtime;
                if (failure instanceof Error error) throw error;
                throw new IllegalStateException("Dashboard transaction failed", failure);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to open dashboard transaction", e);
        }
    }
}
