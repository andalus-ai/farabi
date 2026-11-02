package tech.kayys.farabi.jdbc;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import tech.kayys.farabi.engine.DatasetDeletionReconciliationService;
import tech.kayys.farabi.engine.DatasetDeletionReconciliationService.*;

/** Workspace-scoped read model and append-only persistence for deletion reconciliation. */
public final class JdbcDatasetDeletionReconciliationStore implements Repository {
    private final DataSource source;
    public JdbcDatasetDeletionReconciliationStore(DataSource source){this.source=Objects.requireNonNull(source);}

    @Override public Snapshot snapshot(String workspace){
        if(workspace==null||workspace.isBlank())throw new IllegalArgumentException("workspace required");
        try(Connection c=source.getConnection()){
            List<DatasetState> datasets=new ArrayList<>();
            try(PreparedStatement p=c.prepareStatement("SELECT id,location_ref,lifecycle,storage_bytes FROM farabi_datasets WHERE workspace_id=? ORDER BY id")){
                p.setString(1,workspace);try(ResultSet r=p.executeQuery()){while(r.next())datasets.add(new DatasetState(r.getObject(1,UUID.class),r.getString(2),r.getString(3),r.getLong(4)));}
            }
            List<DeletionState> deletions=new ArrayList<>();
            try(PreparedStatement p=c.prepareStatement("SELECT id,dataset_id,status FROM farabi_dataset_deletions WHERE workspace_id=? ORDER BY id")){
                p.setString(1,workspace);try(ResultSet r=p.executeQuery()){while(r.next())deletions.add(new DeletionState(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3)));}
            }
            long ledgerUsed=0,ledgerReserved=0;
            try(PreparedStatement p=c.prepareStatement("SELECT used_bytes,reserved_bytes FROM farabi_workspace_storage WHERE workspace_id=?")){
                p.setString(1,workspace);try(ResultSet r=p.executeQuery()){if(r.next()){ledgerUsed=r.getLong(1);ledgerReserved=r.getLong(2);}}
            }
            long expectedUsed=0,expectedReserved=0;
            try(PreparedStatement p=c.prepareStatement("SELECT COALESCE((SELECT SUM(storage_bytes) FROM farabi_datasets WHERE workspace_id=? AND lifecycle<>'ARCHIVED'),0),COALESCE((SELECT SUM(reserved_bytes) FROM farabi_storage_reservations WHERE workspace_id=? AND status='RESERVED'),0)")){
                p.setString(1,workspace);p.setString(2,workspace);try(ResultSet r=p.executeQuery()){r.next();expectedUsed=r.getLong(1);expectedReserved=r.getLong(2);}
            }
            return new Snapshot(datasets,deletions,ledgerUsed,ledgerReserved,expectedUsed,expectedReserved);
        }catch(SQLException e){throw new IllegalStateException("read deletion reconciliation snapshot failed",e);}
    }

    @Override public void save(Report report){
        try(Connection c=source.getConnection()){
            c.setAutoCommit(false);
            try(PreparedStatement p=c.prepareStatement("INSERT INTO farabi_dataset_deletion_reconciliation_reports(id,workspace_id,inspected_at,datasets_inspected,deletion_jobs_inspected,finding_count,consistent) VALUES(?,?,?,?,?,?,?)")){
                p.setObject(1,report.id());p.setString(2,report.workspaceId());p.setTimestamp(3,Timestamp.from(report.inspectedAt()));p.setInt(4,report.datasetsInspected());p.setInt(5,report.deletionJobsInspected());p.setInt(6,report.findings().size());p.setBoolean(7,report.consistent());p.executeUpdate();
            }
            try(PreparedStatement p=c.prepareStatement("INSERT INTO farabi_dataset_deletion_reconciliation_findings(id,report_id,workspace_id,finding_type,dataset_id,deletion_job_id,location_ref,expected_bytes,actual_bytes) VALUES(?,?,?,?,?,?,?,?,?)")){
                for(Finding f:report.findings()){
                    p.setObject(1,UUID.randomUUID());p.setObject(2,report.id());p.setString(3,report.workspaceId());p.setString(4,f.type().name());p.setObject(5,f.datasetId());p.setObject(6,f.deletionJobId());p.setString(7,f.locationRef());p.setLong(8,f.expectedBytes());p.setLong(9,f.actualBytes());p.addBatch();
                }
                p.executeBatch();
            }
            c.commit();
        }catch(SQLException e){throw new IllegalStateException("persist deletion reconciliation report failed",e);}
    }

    @Override public List<Report> recent(String workspace,int limit){
        if(limit<1||limit>100)throw new IllegalArgumentException("limit must be 1..100");
        String sql="SELECT id,workspace_id,inspected_at,datasets_inspected,deletion_jobs_inspected FROM farabi_dataset_deletion_reconciliation_reports WHERE workspace_id=? ORDER BY inspected_at DESC,id DESC LIMIT ?";
        try(Connection c=source.getConnection();PreparedStatement p=c.prepareStatement(sql)){
            p.setString(1,workspace);p.setInt(2,limit);
            try(ResultSet r=p.executeQuery()){
                List<Report> out=new ArrayList<>();
                while(r.next()){
                    UUID id=r.getObject(1,UUID.class);List<Finding> findings=new ArrayList<>();
                    try(PreparedStatement q=c.prepareStatement("SELECT finding_type,dataset_id,deletion_job_id,location_ref,expected_bytes,actual_bytes FROM farabi_dataset_deletion_reconciliation_findings WHERE report_id=? AND workspace_id=? ORDER BY id")){
                        q.setObject(1,id);q.setString(2,workspace);try(ResultSet fr=q.executeQuery()){while(fr.next())findings.add(new Finding(FindingType.valueOf(fr.getString(1)),fr.getObject(2,UUID.class),fr.getObject(3,UUID.class),fr.getString(4),fr.getLong(5),fr.getLong(6)));}
                    }
                    out.add(new Report(id,r.getString(2),r.getTimestamp(3).toInstant(),r.getInt(4),r.getInt(5),findings));
                }
                return List.copyOf(out);
            }
        }catch(SQLException e){throw new IllegalStateException("read deletion reconciliation reports failed",e);}
    }
}
