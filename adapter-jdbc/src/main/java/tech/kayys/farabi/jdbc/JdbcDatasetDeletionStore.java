package tech.kayys.farabi.jdbc;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import tech.kayys.farabi.engine.DatasetStorageLifecyclePorts.*;

/** PostgreSQL deletion outbox with row-lock claiming and fencing tokens. */
public final class JdbcDatasetDeletionStore implements Store {
    private final DataSource source;
    public JdbcDatasetDeletionStore(DataSource source){this.source=Objects.requireNonNull(source);}

    @Override public Optional<Deletion> request(UUID datasetId,String workspace,Instant now,int maxAttempts){
        throw new IllegalStateException("direct dataset deletion is disabled; submit a maker-checker approval request");
    }

    private Optional<Deletion> findByDataset(UUID id,String workspace){
        try(Connection c=source.getConnection();PreparedStatement p=c.prepareStatement("SELECT * FROM farabi_dataset_deletions WHERE dataset_id=? AND workspace_id=?")){
            p.setObject(1,id);p.setString(2,workspace);try(ResultSet r=p.executeQuery()){return r.next()?Optional.of(read(r)):Optional.empty();}
        }catch(SQLException e){throw new IllegalStateException(e);}
    }

    @Override public Optional<Deletion> claim(Lease lease){
        try(Connection c=source.getConnection()){
            c.setAutoCommit(false);
            try{
                List<ExpiredLease> exhausted=new ArrayList<>();
                try(PreparedStatement p=c.prepareStatement("SELECT id,workspace_id,dataset_id,attempts FROM farabi_dataset_deletions WHERE status='RUNNING' AND attempts>=max_attempts AND lease_until<=? ORDER BY workspace_id,id FOR UPDATE SKIP LOCKED")){
                    p.setTimestamp(1,Timestamp.from(lease.now()));try(ResultSet r=p.executeQuery()){while(r.next())exhausted.add(new ExpiredLease(r.getObject(1,UUID.class),r.getString(2),r.getObject(3,UUID.class),r.getInt(4)));}
                }
                for(ExpiredLease expired:exhausted){
                    try(PreparedStatement p=c.prepareStatement("UPDATE farabi_dataset_deletions SET status='FAILED',lease_owner=NULL,lease_token=NULL,lease_until=NULL,updated_at=? WHERE id=? AND status='RUNNING'")){
                        p.setTimestamp(1,Timestamp.from(lease.now()));p.setObject(2,expired.jobId());p.executeUpdate();
                    }
                    audit(c,expired.jobId(),expired.workspaceId(),expired.datasetId(),"LEASE_EXHAUSTED",expired.attempts(),null,null,"LEASE_EXPIRED",lease.now());
                }
                String sql="SELECT d.* FROM farabi_dataset_deletions d JOIN farabi_dataset_deletion_approvals approval ON approval.id=d.approval_id AND approval.workspace_id=d.workspace_id AND approval.status='APPROVED' AND approval.deletion_job_id=d.id JOIN farabi_datasets ds ON ds.id=d.dataset_id AND ds.workspace_id=d.workspace_id LEFT JOIN LATERAL (SELECT rp.retention_days FROM farabi_dataset_retention_policies rp WHERE rp.workspace_id=d.workspace_id AND (rp.source_id=ds.source_id OR rp.source_id IS NULL) AND rp.effective_at<=? ORDER BY CASE WHEN rp.source_id=ds.source_id THEN 0 ELSE 1 END,rp.effective_at DESC,rp.created_at DESC LIMIT 1) policy ON TRUE WHERE ds.lifecycle='DELETE_PENDING' AND d.attempts<d.max_attempts AND d.available_at<=? AND (d.status='QUEUED' OR (d.status='RUNNING' AND d.lease_until<=?)) AND NOT EXISTS (SELECT 1 FROM farabi_dataset_legal_holds h WHERE h.dataset_id=d.dataset_id AND h.workspace_id=d.workspace_id AND h.released_at IS NULL) AND (EXISTS (SELECT 1 FROM farabi_dataset_retention_exemptions e WHERE e.dataset_id=d.dataset_id AND e.workspace_id=d.workspace_id AND e.revoked_at IS NULL AND (e.expires_at IS NULL OR e.expires_at>?)) OR ds.created_at + (policy.retention_days * INTERVAL '1 day') <= ?) ORDER BY d.created_at,d.id LIMIT 1 FOR UPDATE OF d,ds SKIP LOCKED";
                Deletion d;
                try(PreparedStatement p=c.prepareStatement(sql)){p.setTimestamp(1,Timestamp.from(lease.now()));p.setTimestamp(2,Timestamp.from(lease.now()));p.setTimestamp(3,Timestamp.from(lease.now()));p.setTimestamp(4,Timestamp.from(lease.now()));p.setTimestamp(5,Timestamp.from(lease.now()));try(ResultSet r=p.executeQuery()){if(!r.next()){c.commit();return Optional.empty();}d=read(r);}}
                UUID token=UUID.randomUUID();
                try(PreparedStatement p=c.prepareStatement("UPDATE farabi_dataset_deletions SET status='RUNNING',attempts=attempts+1,lease_owner=?,lease_token=?,lease_until=?,updated_at=? WHERE id=?")){
                    p.setString(1,lease.workerId());p.setObject(2,token);p.setTimestamp(3,Timestamp.from(lease.until()));p.setTimestamp(4,Timestamp.from(lease.now()));p.setObject(5,d.id());p.executeUpdate();
                }
                audit(c,d.id(),d.workspaceId(),d.datasetId(),"CLAIMED",d.attempts()+1,lease.workerId(),token,null,lease.now());
                c.commit();
                return findById(d.id(),d.workspaceId());
            }catch(Exception e){c.rollback();if(e instanceof RuntimeException re)throw re;throw new IllegalStateException(e);}
            finally{c.setAutoCommit(true);}
        }catch(SQLException e){throw new IllegalStateException("claim dataset deletion failed",e);}
    }

    private Optional<Deletion> findById(UUID id,String workspace){
        try(Connection c=source.getConnection();PreparedStatement p=c.prepareStatement("SELECT * FROM farabi_dataset_deletions WHERE id=? AND workspace_id=?")){
            p.setObject(1,id);p.setString(2,workspace);try(ResultSet r=p.executeQuery()){return r.next()?Optional.of(read(r)):Optional.empty();}
        }catch(SQLException e){throw new IllegalStateException(e);}
    }

    @Override public boolean complete(UUID id,UUID token,Instant now){
        try(Connection c=source.getConnection()){
            c.setAutoCommit(false);
            try{
                UUID dataset;String workspace;long bytes;
                try(PreparedStatement p=c.prepareStatement("SELECT d.dataset_id,d.workspace_id,ds.storage_bytes FROM farabi_dataset_deletions d JOIN farabi_datasets ds ON ds.id=d.dataset_id AND ds.workspace_id=d.workspace_id WHERE d.id=? AND d.status='RUNNING' AND d.lease_token=? AND d.lease_until>? FOR UPDATE OF d,ds")){
                    p.setObject(1,id);p.setObject(2,token);p.setTimestamp(3,Timestamp.from(now));
                    try(ResultSet r=p.executeQuery()){if(!r.next()){c.rollback();return false;}dataset=r.getObject(1,UUID.class);workspace=r.getString(2);bytes=r.getLong(3);}
                }
                int attempt;
                try(PreparedStatement p=c.prepareStatement("SELECT attempts FROM farabi_dataset_deletions WHERE id=?")){
                    p.setObject(1,id);try(ResultSet r=p.executeQuery()){r.next();attempt=r.getInt(1);}
                }
                try(PreparedStatement p=c.prepareStatement("UPDATE farabi_dataset_deletions SET status='SUCCEEDED',lease_owner=NULL,lease_token=NULL,lease_until=NULL,updated_at=? WHERE id=?")){
                    p.setTimestamp(1,Timestamp.from(now));p.setObject(2,id);p.executeUpdate();
                }
                audit(c,id,workspace,dataset,"SUCCEEDED",attempt,null,token,null,now);
                try(PreparedStatement p=c.prepareStatement("UPDATE farabi_datasets SET lifecycle='ARCHIVED',deleted_at=?,updated_at=? WHERE id=? AND workspace_id=?")){
                    p.setTimestamp(1,Timestamp.from(now));p.setTimestamp(2,Timestamp.from(now));p.setObject(3,dataset);p.setString(4,workspace);p.executeUpdate();
                }
                if(bytes>0){
                    try(PreparedStatement p=c.prepareStatement("UPDATE farabi_workspace_storage SET used_bytes=used_bytes-?,updated_at=? WHERE workspace_id=? AND used_bytes>=?")){
                        p.setLong(1,bytes);p.setTimestamp(2,Timestamp.from(now));p.setString(3,workspace);p.setLong(4,bytes);
                        if(p.executeUpdate()!=1)throw new IllegalStateException("workspace storage usage ledger is inconsistent");
                    }
                }
                try(PreparedStatement p=c.prepareStatement("UPDATE farabi_storage_reservations SET status='RELEASED',updated_at=? WHERE dataset_id=? AND workspace_id=? AND status='COMMITTED'")){
                    p.setTimestamp(1,Timestamp.from(now));p.setObject(2,dataset);p.setString(3,workspace);p.executeUpdate();
                }
                c.commit();return true;
            }catch(Exception e){c.rollback();if(e instanceof RuntimeException re)throw re;throw new IllegalStateException(e);}
            finally{c.setAutoCommit(true);}
        }catch(SQLException e){throw new IllegalStateException("complete dataset deletion failed",e);}
    }

    @Override public boolean fail(UUID id,UUID token,Instant retryAt,Instant now){
        try(Connection c=source.getConnection()){
            c.setAutoCommit(false);
            try{
                String workspace;UUID dataset;int attempt;String worker;
                try(PreparedStatement p=c.prepareStatement("SELECT workspace_id,dataset_id,attempts,lease_owner FROM farabi_dataset_deletions WHERE id=? AND status='RUNNING' AND lease_token=? AND lease_until>? FOR UPDATE")){
                    p.setObject(1,id);p.setObject(2,token);p.setTimestamp(3,Timestamp.from(now));
                    try(ResultSet r=p.executeQuery()){if(!r.next()){c.rollback();return false;}workspace=r.getString(1);dataset=r.getObject(2,UUID.class);attempt=r.getInt(3);worker=r.getString(4);}
                }
                String status=attempt<maxAttempts(c,id)?"QUEUED":"FAILED";
                try(PreparedStatement p=c.prepareStatement("UPDATE farabi_dataset_deletions SET status=?,available_at=?,lease_owner=NULL,lease_token=NULL,lease_until=NULL,updated_at=? WHERE id=?")){
                    p.setString(1,status);p.setTimestamp(2,Timestamp.from(retryAt));p.setTimestamp(3,Timestamp.from(now));p.setObject(4,id);p.executeUpdate();
                }
                audit(c,id,workspace,dataset,"QUEUED".equals(status)?"RETRY_SCHEDULED":"FAILED",attempt,worker,token,"OBJECT_DELETE_FAILED",now);
                c.commit();return true;
            }catch(Exception e){c.rollback();if(e instanceof RuntimeException re)throw re;throw new IllegalStateException(e);}
            finally{c.setAutoCommit(true);}
        }catch(SQLException e){throw new IllegalStateException("fail dataset deletion failed",e);}
    }

    private static int maxAttempts(Connection c,UUID id)throws SQLException{
        try(PreparedStatement p=c.prepareStatement("SELECT max_attempts FROM farabi_dataset_deletions WHERE id=?")){
            p.setObject(1,id);try(ResultSet r=p.executeQuery()){if(!r.next())throw new NoSuchElementException("deletion job not found");return r.getInt(1);}
        }
    }

    private record ExpiredLease(UUID jobId,String workspaceId,UUID datasetId,int attempts) {}
    private static void audit(Connection c,UUID job,String workspace,UUID dataset,String type,int attempt,
            String worker,UUID token,String error,Instant now)throws SQLException{
        try(PreparedStatement p=c.prepareStatement("INSERT INTO farabi_dataset_deletion_execution_audit(id,job_id,workspace_id,dataset_id,event_type,attempt,worker_id,lease_token,error_code,occurred_at) VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(job_id,event_type,attempt) DO NOTHING")){
            p.setObject(1,UUID.randomUUID());p.setObject(2,job);p.setString(3,workspace);p.setObject(4,dataset);p.setString(5,type);p.setInt(6,attempt);p.setString(7,worker);p.setObject(8,token);p.setString(9,error);p.setTimestamp(10,Timestamp.from(now));p.executeUpdate();
        }
    }

    public record ExecutionAudit(UUID id,UUID jobId,UUID datasetId,String eventType,int attempt,
            String workerId,UUID leaseToken,String errorCode,Instant occurredAt) {}
    public List<ExecutionAudit> audit(UUID jobId,String workspace,int limit){
        if(limit<1||limit>100)throw new IllegalArgumentException("limit must be 1..100");
        String sql="SELECT a.* FROM farabi_dataset_deletion_execution_audit a JOIN farabi_dataset_deletions d ON d.id=a.job_id AND d.workspace_id=a.workspace_id WHERE a.job_id=? AND a.workspace_id=? ORDER BY a.occurred_at DESC,a.id DESC LIMIT ?";
        try(Connection c=source.getConnection();PreparedStatement p=c.prepareStatement(sql)){
            p.setObject(1,jobId);p.setString(2,workspace);p.setInt(3,limit);
            try(ResultSet r=p.executeQuery()){List<ExecutionAudit> out=new ArrayList<>();while(r.next())out.add(new ExecutionAudit(r.getObject("id",UUID.class),r.getObject("job_id",UUID.class),r.getObject("dataset_id",UUID.class),r.getString("event_type"),r.getInt("attempt"),r.getString("worker_id"),r.getObject("lease_token",UUID.class),r.getString("error_code"),r.getTimestamp("occurred_at").toInstant()));return List.copyOf(out);}
        }catch(SQLException e){throw new IllegalStateException("read deletion execution audit failed",e);}
    }

    private static Deletion read(ResultSet r)throws SQLException{
        Timestamp until=r.getTimestamp("lease_until");
        return new Deletion(r.getObject("id",UUID.class),r.getObject("dataset_id",UUID.class),r.getString("workspace_id"),
                r.getString("location_ref"),r.getString("status"),r.getInt("attempts"),r.getInt("max_attempts"),
                r.getObject("lease_token",UUID.class),until==null?null:until.toInstant(),r.getTimestamp("available_at").toInstant());
    }
}
