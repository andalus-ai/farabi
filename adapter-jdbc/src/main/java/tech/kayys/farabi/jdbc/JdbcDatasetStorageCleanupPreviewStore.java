package tech.kayys.farabi.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import tech.kayys.farabi.engine.DatasetStorageCleanupPreviewService;
import tech.kayys.farabi.engine.DatasetStorageCleanupPreviewService.Candidate;
import tech.kayys.farabi.engine.DatasetStorageCleanupPreviewService.Plan;
import tech.kayys.farabi.engine.DatasetStorageCleanupPreviewService.Plans;

/** Durable workspace-scoped dry-run cleanup proposals. No mutation/deletion methods are exposed. */
public final class JdbcDatasetStorageCleanupPreviewStore implements Plans {
    private final DataSource source;
    public JdbcDatasetStorageCleanupPreviewStore(DataSource source) { this.source=java.util.Objects.requireNonNull(source); }

    @Override public void save(Plan plan) {
        String header="INSERT INTO farabi_storage_cleanup_plans(id,workspace_id,source_report_id,created_by,created_at,expires_at,candidate_count) VALUES(?,?,?,?,?,?,?)";
        String item="INSERT INTO farabi_storage_cleanup_plan_items(plan_id,workspace_id,item_index,location_ref,size_bytes,last_modified) VALUES(?,?,?,?,?,?)";
        try(Connection c=source.getConnection()) {
            c.setAutoCommit(false);
            try {
                try(PreparedStatement p=c.prepareStatement(header)) {
                    p.setObject(1,plan.id()); p.setString(2,plan.workspaceId()); p.setObject(3,plan.sourceReportId());
                    p.setString(4,plan.createdBy()); p.setTimestamp(5,Timestamp.from(plan.createdAt()));
                    p.setTimestamp(6,Timestamp.from(plan.expiresAt())); p.setInt(7,plan.candidates().size()); p.executeUpdate();
                }
                try(PreparedStatement p=c.prepareStatement(item)) {
                    for(int i=0;i<plan.candidates().size();i++) {
                        Candidate candidate=plan.candidates().get(i);
                        p.setObject(1,plan.id()); p.setString(2,plan.workspaceId()); p.setInt(3,i);
                        p.setString(4,candidate.locationRef()); p.setLong(5,candidate.sizeBytes());
                        p.setTimestamp(6,Timestamp.from(candidate.lastModified())); p.addBatch();
                    }
                    p.executeBatch();
                }
                c.commit();
            } catch(Exception e) {
                c.rollback(); if(e instanceof RuntimeException x) throw x;
                throw new IllegalStateException("persist cleanup preview failed",e);
            } finally { c.setAutoCommit(true); }
        } catch(SQLException e) { throw new IllegalStateException("persist cleanup preview failed",e); }
    }

    @Override public Optional<Plan> find(UUID id,String workspace) {
        if(id==null||workspace==null||workspace.isBlank()) throw new IllegalArgumentException("planId and workspace required");
        String sql="SELECT * FROM farabi_storage_cleanup_plans WHERE id=? AND workspace_id=?";
        try(Connection c=source.getConnection();PreparedStatement p=c.prepareStatement(sql)) {
            p.setObject(1,id);p.setString(2,workspace);
            try(ResultSet r=p.executeQuery()) { return r.next()?Optional.of(readPlan(c,r)):Optional.empty(); }
        } catch(SQLException e) { throw new IllegalStateException("read cleanup preview failed",e); }
    }

    @Override public List<Plan> recent(String workspace,int limit) {
        if(workspace==null||workspace.isBlank()||limit<1||limit>100) throw new IllegalArgumentException("invalid workspace or limit");
        String sql="SELECT * FROM farabi_storage_cleanup_plans WHERE workspace_id=? ORDER BY created_at DESC,id DESC LIMIT ?";
        try(Connection c=source.getConnection();PreparedStatement p=c.prepareStatement(sql)) {
            p.setString(1,workspace);p.setInt(2,limit);
            try(ResultSet r=p.executeQuery()) {
                List<Plan> result=new ArrayList<>();
                while(r.next()) result.add(readPlan(c,r));
                return List.copyOf(result);
            }
        } catch(SQLException e) { throw new IllegalStateException("read cleanup previews failed",e); }
    }

    private Plan readPlan(Connection c,ResultSet r)throws SQLException {
        UUID id=r.getObject("id",UUID.class); String workspace=r.getString("workspace_id");
        List<Candidate> candidates=new ArrayList<>();
        try(PreparedStatement p=c.prepareStatement("SELECT location_ref,size_bytes,last_modified FROM farabi_storage_cleanup_plan_items WHERE plan_id=? AND workspace_id=? ORDER BY item_index")) {
            p.setObject(1,id);p.setString(2,workspace);
            try(ResultSet rows=p.executeQuery()) {
                while(rows.next()) candidates.add(new Candidate(rows.getString("location_ref"),rows.getLong("size_bytes"),rows.getTimestamp("last_modified").toInstant()));
            }
        }
        return new Plan(id,workspace,r.getObject("source_report_id",UUID.class),r.getString("created_by"),
                r.getTimestamp("created_at").toInstant(),r.getTimestamp("expires_at").toInstant(),candidates);
    }
}
