package tech.kayys.farabi.jdbc;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import tech.kayys.farabi.engine.DatasetContracts.Dataset;
import tech.kayys.farabi.engine.DatasetImportJobPorts.*;

/** PostgreSQL implementation with atomic submission, row-lock claiming and lease fencing. */
public final class JdbcDatasetImportJobStore implements Store, SubmissionStore {
    private final DataSource source;
    public JdbcDatasetImportJobStore(DataSource source) { this.source=Objects.requireNonNull(source,"source"); }

    @Override public Job registerAndEnqueue(Dataset d,NewJob request) {
        Objects.requireNonNull(d);Objects.requireNonNull(request);
        if(!d.id().equals(request.datasetId())||!d.workspaceId().equals(request.workspaceId()))
            throw new IllegalArgumentException("dataset and job workspace/identity must match");
        String insertDataset="INSERT INTO farabi_datasets(id,workspace_id,name,source_id,format,location_ref,lifecycle,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)";
        String insertJob="INSERT INTO farabi_dataset_import_jobs(id,workspace_id,dataset_id,idempotency_key,status,attempts,max_attempts,available_at,created_at,updated_at) VALUES(?,?,?,?, 'QUEUED',0,?,?,?,?)";
        try(Connection c=source.getConnection()){
            c.setAutoCommit(false);
            try{
                try(PreparedStatement p=c.prepareStatement(insertDataset)){
                    p.setObject(1,d.id());p.setString(2,d.workspaceId());p.setString(3,d.name());p.setString(4,d.sourceId());
                    p.setString(5,d.format().name());p.setString(6,d.locationRef());p.setString(7,d.lifecycle().name());
                    p.setTimestamp(8,Timestamp.from(d.createdAt()));p.setTimestamp(9,Timestamp.from(d.updatedAt()));p.executeUpdate();
                }
                UUID jobId=UUID.randomUUID();
                try(PreparedStatement p=c.prepareStatement(insertJob)){
                    p.setObject(1,jobId);p.setString(2,request.workspaceId());p.setObject(3,request.datasetId());
                    p.setString(4,request.idempotencyKey());p.setInt(5,request.maxAttempts());
                    p.setTimestamp(6,Timestamp.from(request.now()));p.setTimestamp(7,Timestamp.from(request.now()));
                    p.setTimestamp(8,Timestamp.from(request.now()));p.executeUpdate();
                }
                c.commit();
                return find(jobId,request.workspaceId()).orElseThrow();
            }catch(SQLException e){
                c.rollback();
                if("23505".equals(e.getSQLState())){
                    Optional<Job> existing=findByIdempotencyKey(request.workspaceId(),request.idempotencyKey());
                    if(existing.isPresent())return existing.get();
                    throw new IllegalStateException("dataset name already exists in workspace",e);
                }
                throw new IllegalStateException("atomic dataset submission failed",e);
            }catch(RuntimeException e){c.rollback();throw e;}
            finally{c.setAutoCommit(true);}
        }catch(SQLException e){throw new IllegalStateException("atomic dataset submission failed",e);}
    }

    @Override public Job enqueue(NewJob request) {
        UUID id=UUID.randomUUID();
        String sql="INSERT INTO farabi_dataset_import_jobs(id,workspace_id,dataset_id,idempotency_key,status,attempts,max_attempts,available_at,created_at,updated_at) VALUES(?,?,?,?, 'QUEUED',0,?,?,?,?)";
        try(Connection c=source.getConnection();PreparedStatement p=c.prepareStatement(sql)){
            p.setObject(1,id);p.setString(2,request.workspaceId());p.setObject(3,request.datasetId());
            p.setString(4,request.idempotencyKey());p.setInt(5,request.maxAttempts());
            p.setTimestamp(6,Timestamp.from(request.now()));p.setTimestamp(7,Timestamp.from(request.now()));
            p.setTimestamp(8,Timestamp.from(request.now()));p.executeUpdate();
            return find(id,request.workspaceId()).orElseThrow();
        }catch(SQLException e){
            if("23505".equals(e.getSQLState()))return findByIdempotencyKey(request.workspaceId(),request.idempotencyKey()).orElseThrow();
            throw new IllegalStateException("enqueue import job failed",e);
        }
    }

    @Override public Optional<Job> find(UUID id,String workspace) {
        try(Connection c=source.getConnection();PreparedStatement p=c.prepareStatement("SELECT * FROM farabi_dataset_import_jobs WHERE id=? AND workspace_id=?")){
            p.setObject(1,id);p.setString(2,workspace);try(ResultSet r=p.executeQuery()){return r.next()?Optional.of(read(r)):Optional.empty();}
        }catch(SQLException e){throw new IllegalStateException("find import job failed",e);}
    }
    @Override public Optional<Job> findByIdempotencyKey(String workspace,String key) {
        try(Connection c=source.getConnection();PreparedStatement p=c.prepareStatement("SELECT * FROM farabi_dataset_import_jobs WHERE workspace_id=? AND idempotency_key=?")){
            p.setString(1,workspace);p.setString(2,key);try(ResultSet r=p.executeQuery()){return r.next()?Optional.of(read(r)):Optional.empty();}
        }catch(SQLException e){throw new IllegalStateException("find idempotent import job failed",e);}
    }

    @Override public Optional<Job> claim(Lease lease) {
        String select="SELECT * FROM farabi_dataset_import_jobs WHERE attempts < max_attempts AND available_at <= ? AND (status='QUEUED' OR (status='RUNNING' AND lease_until <= ?)) ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED";
        try(Connection c=source.getConnection()){
            c.setAutoCommit(false);
            try{
                Job selected;
                try(PreparedStatement p=c.prepareStatement(select)){
                    p.setTimestamp(1,Timestamp.from(lease.now()));p.setTimestamp(2,Timestamp.from(lease.now()));
                    try(ResultSet r=p.executeQuery()){if(!r.next()){c.commit();return Optional.empty();}selected=read(r);}
                }
                UUID token=UUID.randomUUID();
                try(PreparedStatement p=c.prepareStatement("UPDATE farabi_dataset_import_jobs SET status='RUNNING',attempts=attempts+1,lease_owner=?,lease_token=?,lease_until=?,updated_at=?,error_code=NULL,error_message=NULL WHERE id=?")){
                    p.setString(1,lease.workerId());p.setObject(2,token);p.setTimestamp(3,Timestamp.from(lease.leaseUntil()));
                    p.setTimestamp(4,Timestamp.from(lease.now()));p.setObject(5,selected.id());p.executeUpdate();
                }
                c.commit();return find(selected.id(),selected.workspaceId());
            }catch(Exception e){c.rollback();if(e instanceof RuntimeException re)throw re;throw new IllegalStateException(e);}
            finally{c.setAutoCommit(true);}
        }catch(SQLException e){throw new IllegalStateException("claim import job failed",e);}
    }

    @Override public boolean renew(UUID id,UUID token,Instant leaseUntil,Instant now) {
        if(!leaseUntil.isAfter(now))throw new IllegalArgumentException("leaseUntil must be after now");
        return update("UPDATE farabi_dataset_import_jobs SET lease_until=?,updated_at=? WHERE id=? AND status='RUNNING' AND lease_token=? AND lease_until>?",
                p->{p.setTimestamp(1,Timestamp.from(leaseUntil));p.setTimestamp(2,Timestamp.from(now));p.setObject(3,id);p.setObject(4,token);p.setTimestamp(5,Timestamp.from(now));});
    }
    @Override public boolean complete(UUID id,UUID token,Instant now) {
        return update("UPDATE farabi_dataset_import_jobs SET status='SUCCEEDED',lease_owner=NULL,lease_token=NULL,lease_until=NULL,updated_at=? WHERE id=? AND status='RUNNING' AND lease_token=? AND lease_until>?",
                p->{p.setTimestamp(1,Timestamp.from(now));p.setObject(2,id);p.setObject(3,token);p.setTimestamp(4,Timestamp.from(now));});
    }
    @Override public boolean fail(UUID id,UUID token,String code,String message,Instant retryAt,Instant now) {
        if(code==null||code.isBlank())throw new IllegalArgumentException("errorCode required");
        return update("UPDATE farabi_dataset_import_jobs SET status=CASE WHEN attempts < max_attempts THEN 'QUEUED' ELSE 'FAILED' END,available_at=?,lease_owner=NULL,lease_token=NULL,lease_until=NULL,error_code=?,error_message=?,updated_at=? WHERE id=? AND status='RUNNING' AND lease_token=? AND lease_until>?",
                p->{p.setTimestamp(1,Timestamp.from(retryAt));p.setString(2,code);p.setString(3,truncate(message,2000));p.setTimestamp(4,Timestamp.from(now));p.setObject(5,id);p.setObject(6,token);p.setTimestamp(7,Timestamp.from(now));});
    }
    @FunctionalInterface private interface Binder{void bind(PreparedStatement p)throws SQLException;}
    private boolean update(String sql,Binder binder){try(Connection c=source.getConnection();PreparedStatement p=c.prepareStatement(sql)){binder.bind(p);return p.executeUpdate()==1;}catch(SQLException e){throw new IllegalStateException("update import job failed",e);}}
    private static Job read(ResultSet r)throws SQLException{
        Timestamp until=r.getTimestamp("lease_until"),available=r.getTimestamp("available_at");
        return new Job(r.getObject("id",UUID.class),r.getString("workspace_id"),r.getObject("dataset_id",UUID.class),
            r.getString("idempotency_key"),Status.valueOf(r.getString("status")),r.getInt("attempts"),r.getInt("max_attempts"),
            r.getString("lease_owner"),r.getObject("lease_token",UUID.class),until==null?null:until.toInstant(),available.toInstant(),
            r.getString("error_code"),r.getString("error_message"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant());
    }
    private static String truncate(String s,int n){return s==null?null:s.length()<=n?s:s.substring(0,n);}
}
