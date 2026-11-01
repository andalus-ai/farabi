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
        if(maxAttempts<1)throw new IllegalArgumentException("maxAttempts must be positive");
        try(Connection c=source.getConnection()){
            c.setAutoCommit(false);
            try{
                String select="SELECT location_ref,deletion_requested_at FROM farabi_datasets WHERE id=? AND workspace_id=? FOR UPDATE";
                String location; Timestamp requested;
                try(PreparedStatement p=c.prepareStatement(select)){
                    p.setObject(1,datasetId);p.setString(2,workspace);
                    try(ResultSet r=p.executeQuery()){if(!r.next()){c.rollback();return Optional.empty();}
                        location=r.getString(1);requested=r.getTimestamp(2);}
                }
                if(requested==null){
                    try(PreparedStatement p=c.prepareStatement("UPDATE farabi_datasets SET lifecycle='DELETE_PENDING',deletion_requested_at=?,updated_at=? WHERE id=? AND workspace_id=?")){
                        p.setTimestamp(1,Timestamp.from(now));p.setTimestamp(2,Timestamp.from(now));p.setObject(3,datasetId);p.setString(4,workspace);p.executeUpdate();
                    }
                    try(PreparedStatement p=c.prepareStatement("INSERT INTO farabi_dataset_deletions(id,workspace_id,dataset_id,location_ref,status,attempts,max_attempts,available_at,created_at,updated_at) VALUES(?,?,?,?,'QUEUED',0,?,?,?,?)")){
                        p.setObject(1,UUID.randomUUID());p.setString(2,workspace);p.setObject(3,datasetId);p.setString(4,location);
                        p.setInt(5,maxAttempts);p.setTimestamp(6,Timestamp.from(now));p.setTimestamp(7,Timestamp.from(now));p.setTimestamp(8,Timestamp.from(now));p.executeUpdate();
                    }
                }
                c.commit();
                return findByDataset(datasetId,workspace);
            }catch(Exception e){c.rollback();if(e instanceof RuntimeException re)throw re;throw new IllegalStateException("request dataset deletion failed",e);}
            finally{c.setAutoCommit(true);}
        }catch(SQLException e){throw new IllegalStateException("request dataset deletion failed",e);}
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
                try(PreparedStatement p=c.prepareStatement("UPDATE farabi_dataset_deletions SET status='FAILED',lease_owner=NULL,lease_token=NULL,lease_until=NULL,updated_at=? WHERE status='RUNNING' AND attempts>=max_attempts AND lease_until<=?")){
                    p.setTimestamp(1,Timestamp.from(lease.now()));p.setTimestamp(2,Timestamp.from(lease.now()));p.executeUpdate();
                }
                String sql="SELECT * FROM farabi_dataset_deletions WHERE attempts<max_attempts AND available_at<=? AND (status='QUEUED' OR (status='RUNNING' AND lease_until<=?)) ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED";
                Deletion d;
                try(PreparedStatement p=c.prepareStatement(sql)){p.setTimestamp(1,Timestamp.from(lease.now()));p.setTimestamp(2,Timestamp.from(lease.now()));try(ResultSet r=p.executeQuery()){if(!r.next()){c.commit();return Optional.empty();}d=read(r);}}
                UUID token=UUID.randomUUID();
                try(PreparedStatement p=c.prepareStatement("UPDATE farabi_dataset_deletions SET status='RUNNING',attempts=attempts+1,lease_owner=?,lease_token=?,lease_until=?,updated_at=? WHERE id=?")){
                    p.setString(1,lease.workerId());p.setObject(2,token);p.setTimestamp(3,Timestamp.from(lease.until()));p.setTimestamp(4,Timestamp.from(lease.now()));p.setObject(5,d.id());p.executeUpdate();
                }
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
                try(PreparedStatement p=c.prepareStatement("UPDATE farabi_dataset_deletions SET status='SUCCEEDED',lease_owner=NULL,lease_token=NULL,lease_until=NULL,updated_at=? WHERE id=?")){
                    p.setTimestamp(1,Timestamp.from(now));p.setObject(2,id);p.executeUpdate();
                }
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
        String sql="UPDATE farabi_dataset_deletions SET status=CASE WHEN attempts<max_attempts THEN 'QUEUED' ELSE 'FAILED' END,available_at=?,lease_owner=NULL,lease_token=NULL,lease_until=NULL,updated_at=? WHERE id=? AND status='RUNNING' AND lease_token=? AND lease_until>?";
        try(Connection c=source.getConnection();PreparedStatement p=c.prepareStatement(sql)){
            p.setTimestamp(1,Timestamp.from(retryAt));p.setTimestamp(2,Timestamp.from(now));p.setObject(3,id);p.setObject(4,token);p.setTimestamp(5,Timestamp.from(now));return p.executeUpdate()==1;
        }catch(SQLException e){throw new IllegalStateException("fail dataset deletion failed",e);}
    }

    private static Deletion read(ResultSet r)throws SQLException{
        Timestamp until=r.getTimestamp("lease_until");
        return new Deletion(r.getObject("id",UUID.class),r.getObject("dataset_id",UUID.class),r.getString("workspace_id"),
                r.getString("location_ref"),r.getString("status"),r.getInt("attempts"),r.getInt("max_attempts"),
                r.getObject("lease_token",UUID.class),until==null?null:until.toInstant(),r.getTimestamp("available_at").toInstant());
    }
}
