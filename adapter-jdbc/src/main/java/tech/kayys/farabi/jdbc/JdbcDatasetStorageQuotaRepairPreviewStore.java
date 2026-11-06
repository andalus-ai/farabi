package tech.kayys.farabi.jdbc;

import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import tech.kayys.farabi.engine.DatasetStorageQuotaRepairPreviewService.Plan;
import tech.kayys.farabi.engine.DatasetStorageQuotaRepairPreviewService.Plans;

/** Append-only JDBC persistence for workspace quota repair proposals. */
public final class JdbcDatasetStorageQuotaRepairPreviewStore implements Plans {
    private final DataSource source;
    public JdbcDatasetStorageQuotaRepairPreviewStore(DataSource source) { this.source = Objects.requireNonNull(source); }

    @Override public void save(Plan p) {
        String sql = """
            INSERT INTO farabi_storage_quota_repair_previews
            (id,workspace_id,created_by,created_at,expires_at,reconciliation_report_id,quota_bytes,
             ledger_used_bytes,proposed_used_bytes,used_delta_bytes,ledger_reserved_bytes,
             proposed_reserved_bytes,reserved_delta_bytes,committed_reservation_bytes,applicable)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """;
        try (Connection c=source.getConnection(); PreparedStatement s=c.prepareStatement(sql)) {
            s.setObject(1,p.id()); s.setString(2,p.workspaceId()); s.setString(3,p.createdBy());
            s.setTimestamp(4,Timestamp.from(p.createdAt())); s.setTimestamp(5,Timestamp.from(p.expiresAt()));
            s.setObject(6,p.reconciliationReportId()); s.setLong(7,p.quotaBytes());
            s.setLong(8,p.ledgerUsedBytes()); s.setLong(9,p.proposedUsedBytes()); s.setLong(10,p.usedDeltaBytes());
            s.setLong(11,p.ledgerReservedBytes()); s.setLong(12,p.proposedReservedBytes());
            s.setLong(13,p.reservedDeltaBytes()); s.setLong(14,p.committedReservationBytes());
            s.setBoolean(15,p.applicable()); s.executeUpdate();
        } catch(SQLException e) { throw new IllegalStateException("save quota repair preview failed",e); }
    }

    @Override public Optional<Plan> find(UUID id,String workspace) {
        String sql="SELECT * FROM farabi_storage_quota_repair_previews WHERE id=? AND workspace_id=?";
        try(Connection c=source.getConnection();PreparedStatement s=c.prepareStatement(sql)) {
            s.setObject(1,id); s.setString(2,workspace);
            try(ResultSet r=s.executeQuery()) { return r.next()?Optional.of(map(r)):Optional.empty(); }
        } catch(SQLException e) { throw new IllegalStateException("read quota repair preview failed",e); }
    }

    @Override public List<Plan> recent(String workspace,int limit) {
        if(limit<1||limit>100) throw new IllegalArgumentException("limit must be between 1 and 100");
        String sql="SELECT * FROM farabi_storage_quota_repair_previews WHERE workspace_id=? ORDER BY created_at DESC,id DESC LIMIT ?";
        try(Connection c=source.getConnection();PreparedStatement s=c.prepareStatement(sql)) {
            s.setString(1,workspace); s.setInt(2,limit);
            try(ResultSet r=s.executeQuery()) { List<Plan> out=new ArrayList<>(); while(r.next()) out.add(map(r)); return List.copyOf(out); }
        } catch(SQLException e) { throw new IllegalStateException("list quota repair previews failed",e); }
    }

    private static Plan map(ResultSet r) throws SQLException {
        return new Plan(r.getObject("id",UUID.class),r.getString("workspace_id"),r.getString("created_by"),
            r.getTimestamp("created_at").toInstant(),r.getTimestamp("expires_at").toInstant(),
            r.getObject("reconciliation_report_id",UUID.class),r.getLong("quota_bytes"),
            r.getLong("ledger_used_bytes"),r.getLong("proposed_used_bytes"),r.getLong("used_delta_bytes"),
            r.getLong("ledger_reserved_bytes"),r.getLong("proposed_reserved_bytes"),r.getLong("reserved_delta_bytes"),
            r.getLong("committed_reservation_bytes"),r.getBoolean("applicable"));
    }
}