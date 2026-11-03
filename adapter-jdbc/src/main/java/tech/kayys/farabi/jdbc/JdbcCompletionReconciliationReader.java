package tech.kayys.farabi.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import tech.kayys.farabi.engine.CompletionReconciliation;
import tech.kayys.farabi.engine.QueryPorts.CompletionReconciliationReader;

/**
 * Reads execution lifecycle and worker lease metadata in one workspace-scoped
 * statement so reconciliation never combines observations from separate reads.
 */
public final class JdbcCompletionReconciliationReader implements CompletionReconciliationReader {
    private static final String SQL = """
            SELECT id, workspace_id, status, lease_owner, lease_fence, lease_expires_at
            FROM farabi_query_executions
            WHERE id = ? AND workspace_id = ?
            """;

    private final DataSource dataSource;

    public JdbcCompletionReconciliationReader(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public CompletionReconciliation.ReadResult read(UUID executionId, String workspaceId) {
        Objects.requireNonNull(executionId, "executionId");
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new IllegalArgumentException("workspaceId required");
        }

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SQL)) {
            statement.setObject(1, executionId);
            statement.setString(2, workspaceId);

            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return CompletionReconciliation.ReadResult.missing();
                }

                Timestamp expiry = result.getTimestamp("lease_expires_at");
                CompletionReconciliation.Snapshot snapshot =
                        new CompletionReconciliation.Snapshot(
                                result.getObject("id", UUID.class),
                                result.getString("workspace_id"),
                                tech.kayys.farabi.engine.QueryContracts.Status.valueOf(
                                        result.getString("status")),
                                result.getString("lease_owner"),
                                result.getLong("lease_fence"),
                                expiry == null ? null : expiry.toInstant());

                // A primary-key lookup must return at most one row.
                if (result.next()) {
                    return CompletionReconciliation.ReadResult.unavailable();
                }
                return CompletionReconciliation.ReadResult.available(snapshot);
            }
        } catch (SQLException | IllegalArgumentException exception) {
            // A failed or malformed authoritative read is uncertainty, never
            // evidence that the completion did or did not commit.
            return CompletionReconciliation.ReadResult.unavailable();
        }
    }
}
