package tech.kayys.farabi.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Objects;
import javax.sql.DataSource;
import tech.kayys.farabi.engine.DatasetStorageInventoryAuditPorts;
import tech.kayys.farabi.engine.DatasetStorageInventoryAuditPorts.Event;
import tech.kayys.farabi.engine.DatasetStorageInventoryAuditPorts.EventType;

/** Append-only JDBC audit writer and workspace-scoped audit reader. */
public final class JdbcDatasetStorageInventoryAuditLog implements DatasetStorageInventoryAuditPorts.AuditLog {
    private static final int MAX_LIMIT = 1_000;

    private final DataSource source;

    public JdbcDatasetStorageInventoryAuditLog(DataSource source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    @Override
    public void append(Event event) {
        Objects.requireNonNull(event, "event");
        String sql = "INSERT INTO farabi_storage_inventory_audit"
                + "(id,workspace_id,job_id,actor_id,event_type,attempt,report_id,error_code,occurred_at) "
                + "VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(job_id,event_type,attempt) DO NOTHING";
        try (Connection connection = source.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, event.id());
            statement.setString(2, event.workspaceId());
            statement.setObject(3, event.jobId());
            statement.setString(4, event.actorId());
            statement.setString(5, event.type().name());
            statement.setInt(6, event.attempt());
            statement.setObject(7, event.reportId());
            statement.setString(8, event.errorCode());
            statement.setTimestamp(9, Timestamp.from(event.occurredAt()));
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("append storage inventory audit event failed", failure);
        }
    }

    /**
     * Reads newest events for a workspace. A null job ID intentionally means
     * workspace-wide history; tenant/workspace filtering is never optional.
     */
    @Override
    public List<Event> recent(String workspaceId, UUID jobId, int limit) {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new IllegalArgumentException("workspaceId is required");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }

        String sql = "SELECT id,workspace_id,job_id,actor_id,event_type,attempt,report_id,error_code,occurred_at "
                + "FROM farabi_storage_inventory_audit WHERE workspace_id=?"
                + (jobId == null ? "" : " AND job_id=?")
                + " ORDER BY occurred_at DESC, id DESC LIMIT ?";
        try (Connection connection = source.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, workspaceId);
            if (jobId != null) statement.setObject(index++, jobId);
            statement.setInt(index, limit);

            List<Event> events = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String reportId = rows.getString("report_id");
                    Timestamp occurredAt = rows.getTimestamp("occurred_at");
                    events.add(new Event(
                            UUID.fromString(rows.getString("id")),
                            rows.getString("workspace_id"),
                            UUID.fromString(rows.getString("job_id")),
                            rows.getString("actor_id"),
                            EventType.valueOf(rows.getString("event_type")),
                            rows.getInt("attempt"),
                            reportId == null ? null : UUID.fromString(reportId),
                            rows.getString("error_code"),
                            occurredAt.toInstant()));
                }
            }
            return List.copyOf(events);
        } catch (SQLException failure) {
            throw new IllegalStateException("read storage inventory audit events failed", failure);
        }
    }
}
