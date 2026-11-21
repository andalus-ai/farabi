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

/** Append-only JDBC audit writer. Duplicate delivery of the same logical event is harmless. */
public final class JdbcDatasetStorageInventoryAuditLog implements DatasetStorageInventoryAuditPorts.AuditLog {
    private final DataSource source;
    public JdbcDatasetStorageInventoryAuditLog(DataSource source) { this.source = Objects.requireNonNull(source); }


    @Override public void append(Event event) {
        String sql = "INSERT INTO farabi_storage_inventory_audit(id,workspace_id,job_id,actor_id,event_type,attempt,report_id,error_code,occurred_at) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(job_id,event_type,attempt) DO NOTHING";
        try (Connection c=source.getConnection(); PreparedStatement p=c.prepareStatement(sql)) {
            p.setObject(1,event.id()); p.setString(2,event.workspaceId()); p.setObject(3,event.jobId());
            p.setString(4,event.actorId()); p.setString(5,event.type().name()); p.setInt(6,event.attempt());
            p.setObject(7,event.reportId()); p.setString(8,event.errorCode()); p.setTimestamp(9,Timestamp.from(event.occurredAt()));
            p.executeUpdate();
        } catch (SQLException e) { throw new IllegalStateException("append storage inventory audit event failed",e); }
    }

    @Override public List<Event> recent(String workspaceId, UUID jobId, int limit) {
        if (workspaceId == null || workspaceId.isBlank()) throw new IllegalArgumentException("workspaceId required");
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be 1..1000");
        String sql = jobId == null
                ? "SELECT id,workspace_id,job_id,actor_id,event_type,attempt,report_id,error_code,occurred_at "
                    + "FROM farabi_storage_inventory_audit WHERE workspace_id=? ORDER BY occurred_at DESC LIMIT ?"
                : "SELECT id,workspace_id,job_id,actor_id,event_type,attempt,report_id,error_code,occurred_at "
                    + "FROM farabi_storage_inventory_audit WHERE workspace_id=? AND job_id=? "
                    + "ORDER BY occurred_at DESC LIMIT ?";
        try (Connection c = source.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, workspaceId);
            if (jobId == null) p.setInt(2, limit);
            else { p.setObject(2, jobId); p.setInt(3, limit); }
            try (ResultSet rows = p.executeQuery()) {
                List<Event> events = new ArrayList<>();
                while (rows.next()) {
                    events.add(new Event(rows.getObject("id", UUID.class), rows.getString("workspace_id"),
                            rows.getObject("job_id", UUID.class), rows.getString("actor_id"),
                            EventType.valueOf(rows.getString("event_type")), rows.getInt("attempt"),
                            rows.getObject("report_id", UUID.class), rows.getString("error_code"),
                            rows.getTimestamp("occurred_at").toInstant()));
                }
                return List.copyOf(events);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("read storage inventory audit events failed", e);
        }
    }
}
