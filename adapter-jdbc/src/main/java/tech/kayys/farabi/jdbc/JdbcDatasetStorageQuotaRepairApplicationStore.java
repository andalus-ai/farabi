package tech.kayys.farabi.jdbc;

import java.sql.*;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import tech.kayys.farabi.engine.DatasetStorageQuotaRepairApplicationService;
import tech.kayys.farabi.engine.DatasetStorageQuotaRepairApplicationService.Application;
import tech.kayys.farabi.engine.DatasetStorageQuotaRepairApplicationService.Applications;
import tech.kayys.farabi.engine.DatasetStorageQuotaRepairApplicationService.StalePreviewException;
import tech.kayys.farabi.engine.DatasetStorageQuotaRepairApplicationService.InapplicablePreviewException;
import tech.kayys.farabi.engine.DatasetStorageQuotaRepairPreviewService.Plan;

/** Applies an approved quota proposal with row locks, fresh aggregate checks, and an atomic audit record. */
public final class JdbcDatasetStorageQuotaRepairApplicationStore implements Applications {
    private final DataSource source;
    public JdbcDatasetStorageQuotaRepairApplicationStore(DataSource source) { this.source=Objects.requireNonNull(source); }

    @Override public Optional<Application> findByPreview(UUID previewId,String workspace) {
        String sql="SELECT * FROM farabi_storage_quota_repair_applications WHERE preview_id=? AND workspace_id=?";
        try(Connection c=source.getConnection();PreparedStatement p=c.prepareStatement(sql)) {
            p.setObject(1,previewId);p.setString(2,workspace);
            try(ResultSet r=p.executeQuery()){return r.next()?Optional.of(read(r)):Optional.empty();}
        } catch(SQLException e) { throw new IllegalStateException("read quota repair application failed",e); }
    }

    @Override public Application apply(Plan supplied,String approver,Instant now) {
        Objects.requireNonNull(supplied); Objects.requireNonNull(now);
        try(Connection c=source.getConnection()) {
            c.setAutoCommit(false);
            try {
                // Lock the immutable preview first: concurrent approvals serialize on this row.
                Plan persisted;
                try(PreparedStatement p=c.prepareStatement("SELECT * FROM farabi_storage_quota_repair_previews WHERE id=? AND workspace_id=? FOR UPDATE")) {
                    p.setObject(1,supplied.id());p.setString(2,supplied.workspaceId());
                    try(ResultSet r=p.executeQuery()) {
                        if(!r.next()) throw new java.util.NoSuchElementException("quota repair preview not found");
                        persisted=readPlan(r);
                    }
                }
                Optional<Application> prior=findByPreview(c,persisted.id(),persisted.workspaceId());
                if(prior.isPresent()){c.commit();return prior.get();}
                if(!persisted.equals(supplied)) throw new StalePreviewException("preview contents changed; create a new preview");
                if(!now.isBefore(persisted.expiresAt())) throw new StalePreviewException("quota repair preview has expired");
                if(!persisted.applicable()) throw new InapplicablePreviewException("quota repair preview is not applicable");

                long quota,ledgerUsed,ledgerReserved;
                try(PreparedStatement p=c.prepareStatement("SELECT quota_bytes,used_bytes,reserved_bytes FROM farabi_workspace_storage WHERE workspace_id=? FOR UPDATE")) {
                    p.setString(1,persisted.workspaceId());
                    try(ResultSet r=p.executeQuery()) {
                        if(!r.next()) throw new StalePreviewException("workspace storage ledger no longer exists");
                        quota=r.getLong(1);ledgerUsed=r.getLong(2);ledgerReserved=r.getLong(3);
                    }
                }
                long observedUsed=scalar(c,"SELECT COALESCE(SUM(storage_bytes),0) FROM farabi_datasets WHERE workspace_id=? AND lifecycle<>'ARCHIVED'",persisted.workspaceId());
                long observedReserved=scalar(c,"SELECT COALESCE(SUM(reserved_bytes),0) FROM farabi_storage_reservations WHERE workspace_id=? AND status='RESERVED'",persisted.workspaceId());
                long committed=scalar(c,"SELECT COALESCE(SUM(r.reserved_bytes),0) FROM farabi_storage_reservations r JOIN farabi_datasets d ON d.id=r.dataset_id AND d.workspace_id=r.workspace_id WHERE r.workspace_id=? AND r.status='COMMITTED'",persisted.workspaceId());

                if(quota!=persisted.quotaBytes() || ledgerUsed!=persisted.ledgerUsedBytes()
                        || ledgerReserved!=persisted.ledgerReservedBytes()
                        || observedUsed!=persisted.proposedUsedBytes()
                        || observedReserved!=persisted.proposedReservedBytes()
                        || committed!=persisted.committedReservationBytes())
                    throw new StalePreviewException("workspace accounting changed after preview; create a fresh preview");
                if(committed!=observedUsed || Math.addExact(observedUsed,observedReserved)>quota)
                    throw new InapplicablePreviewException("current accounting invariants do not permit this repair");

                try(PreparedStatement p=c.prepareStatement("UPDATE farabi_workspace_storage SET used_bytes=?,reserved_bytes=?,updated_at=? WHERE workspace_id=? AND quota_bytes=? AND used_bytes=? AND reserved_bytes=?")) {
                    p.setLong(1,observedUsed);p.setLong(2,observedReserved);p.setTimestamp(3,Timestamp.from(now));
                    p.setString(4,persisted.workspaceId());p.setLong(5,quota);p.setLong(6,ledgerUsed);p.setLong(7,ledgerReserved);
                    if(p.executeUpdate()!=1) throw new StalePreviewException("workspace accounting changed during approval");
                }
                Application result=new Application(UUID.randomUUID(),persisted.id(),persisted.workspaceId(),approver,now,
                        ledgerUsed,observedUsed,ledgerReserved,observedReserved);
                insertApplication(c,result);
                c.commit();
                return result;
            } catch(Exception e) {
                c.rollback();
                if(e instanceof RuntimeException re) throw re;
                throw new IllegalStateException("apply quota repair failed",e);
            } finally { c.setAutoCommit(true); }
        } catch(SQLException e) { throw new IllegalStateException("apply quota repair failed",e); }
    }

    private static long scalar(Connection c,String sql,String workspace)throws SQLException {
        try(PreparedStatement p=c.prepareStatement(sql)){p.setString(1,workspace);try(ResultSet r=p.executeQuery()){if(!r.next())throw new SQLException("aggregate returned no row");return r.getLong(1);}}
    }
    private static Optional<Application> findByPreview(Connection c,UUID id,String workspace)throws SQLException {
        try(PreparedStatement p=c.prepareStatement("SELECT * FROM farabi_storage_quota_repair_applications WHERE preview_id=? AND workspace_id=?")) {
            p.setObject(1,id);p.setString(2,workspace);try(ResultSet r=p.executeQuery()){return r.next()?Optional.of(read(r)):Optional.empty();}
        }
    }
    private static void insertApplication(Connection c,Application a)throws SQLException {
        String sql="INSERT INTO farabi_storage_quota_repair_applications(id,preview_id,workspace_id,approved_by,applied_at,previous_used_bytes,applied_used_bytes,previous_reserved_bytes,applied_reserved_bytes) VALUES(?,?,?,?,?,?,?,?,?)";
        try(PreparedStatement p=c.prepareStatement(sql)){p.setObject(1,a.id());p.setObject(2,a.previewId());p.setString(3,a.workspaceId());p.setString(4,a.approvedBy());p.setTimestamp(5,Timestamp.from(a.appliedAt()));p.setLong(6,a.previousUsedBytes());p.setLong(7,a.appliedUsedBytes());p.setLong(8,a.previousReservedBytes());p.setLong(9,a.appliedReservedBytes());p.executeUpdate();}
    }
    private static Application read(ResultSet r)throws SQLException {
        return new Application(r.getObject("id",UUID.class),r.getObject("preview_id",UUID.class),r.getString("workspace_id"),
            r.getString("approved_by"),r.getTimestamp("applied_at").toInstant(),r.getLong("previous_used_bytes"),
            r.getLong("applied_used_bytes"),r.getLong("previous_reserved_bytes"),r.getLong("applied_reserved_bytes"));
    }
    private static Plan readPlan(ResultSet r)throws SQLException {
        return new Plan(r.getObject("id",UUID.class),r.getString("workspace_id"),r.getString("created_by"),
            r.getTimestamp("created_at").toInstant(),r.getTimestamp("expires_at").toInstant(),
            r.getObject("reconciliation_report_id",UUID.class),r.getLong("quota_bytes"),
            r.getLong("ledger_used_bytes"),r.getLong("proposed_used_bytes"),r.getLong("used_delta_bytes"),
            r.getLong("ledger_reserved_bytes"),r.getLong("proposed_reserved_bytes"),r.getLong("reserved_delta_bytes"),
            r.getLong("committed_reservation_bytes"),r.getBoolean("applicable"));
    }
}