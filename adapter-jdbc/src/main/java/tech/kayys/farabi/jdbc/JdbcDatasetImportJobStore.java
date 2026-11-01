package tech.kayys.farabi.jdbc;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import tech.kayys.farabi.engine.DatasetImportJobPorts.*;

/** PostgreSQL implementation with row-lock claiming and lease-token fencing. */
public final class JdbcDatasetImportJobStore implements Store {
    private final DataSource source;

    public JdbcDatasetImportJobStore(DataSource source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    @Override
    public Job enqueue(NewJob request) {
        Objects.requireNonNull(request, "request");
        UUID id = UUID.randomUUID();
        String sql = "INSERT INTO farabi_dataset_import_jobs(id,workspace_id,dataset_id,idempotency_key,status,attempts,max_attempts,created_at,updated_at) VALUES(?,?,?,?, 'QUEUED',0,?,?,?)";
        try (Connection c = source.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setObject(1,id); p.setString(2,request.workspaceId()); p.setObject(3,request.datasetId());
            p.setString(4,request.idempotencyKey()); p.setInt(5,request.maxAttempts());
            p.setTimestamp(6,Timestamp.from(request.now())); p.setTimestamp(7,Timestamp.from(request.now()));
            p.executeUpdate();
            return find(id, request.workspaceId()).orElseThrow();
        } catch (SQLException e) {
            if (!"23505".equals(e.getSQLState())) throw new IllegalStateException("enqueue import job failed",e);
            return findByIdempotencyKey(request.workspaceId(),request.idempotencyKey())
                    .orElseThrow(() -> new IllegalStateException("idempotency conflict without existing job",e));
        }
    }

    @Override
    public Optional<Job> find(UUID id, String workspaceId) {
        String sql = "SELECT * FROM farabi_dataset_import_jobs WHERE id=? AND workspace_id=?";
        try (Connection c=source.getConnection(); PreparedStatement p=c.prepareStatement(sql)) {
            p.setObject(1,id); p.setString(2,workspaceId);
            try(ResultSet r=p.executeQuery()){ return r.next()?Optional.of(read(r)):Optional.empty(); }
        } catch(SQLException e){ throw new IllegalStateException("find import job failed",e); }
    }

    @Override\n    public Optional<Job> findByIdempotencyKey(String workspaceId,String key) {
        String sql="SELECT * FROM farabi_dataset_import_jobs WHERE workspace_id=? AND idempotency_key=?";
        try(Connection c=source.getConnection();PreparedStatement p=c.prepareStatement(sql)){
            p.setString(1,workspaceId);p.setString(2,key);
            try(ResultSet r=p.executeQuery()){return r.next()?Optional.of(read(r)):Optional.empty();}
        }catch(SQLException e){throw new IllegalStateException("find idempotent import job failed",e);}
    }

    @Override
    public Optional<Job> claim(Lease lease) {
        String select = "SELECT * FROM farabi_dataset_import_jobs WHERE attempts < max_attempts AND (status='QUEUED' OR (status='RUNNING' AND lease_until <= ?)) ORDER BY created_at,id FOR UPDATE SKIP LOCKED LIMIT 1";
        String update = "UPDATE farabi_dataset_import_jobs SET status='RUNNING',attempts=attempts+1,lease_owner=?,lease_token=?,lease_until=?,updated_at=?,error_code=NULL,error_message=NULL WHERE id=?";
        try(Connection c=source.getConnection()){
            c.setAutoCommit(false);
            try {
                Job selected;
                try(PreparedStatement p=c.prepareStatement(select)){
                    p.setTimestamp(1,Timestamp.from(lease.now()));
                    try(ResultSet r=p.executeQuery()){if(!r.next()){c.commit();return Optional.empty();}selected=read(r);}
                }
                UUID token=UUID.randomUUID();
                try(PreparedStatement p=c.prepareStatement(update)){
                    p.setString(1,lease.workerId());p.setObject(2,token);
                    p.setTimestamp(3,Timestamp.from(lease.leaseUntil()));p.setTimestamp(4,Timestamp.from(lease.now()));
                    p.setObject(5,selected.id());p.executeUpdate();
                }
                c.commit();
                return find(selected.id(),selected.workspaceId());
            } catch(Exception e){c.rollback();if(e instanceof RuntimeException re)throw re;throw new IllegalStateException(e);}
            finally{c.setAutoCommit(true);}
        }catch(SQLException e){throw new IllegalStateException("claim import job failed",e);}
    }

    @Override
    public boolean complete(UUID id, UUID token, Instant now) {
        return fencedUpdate("UPDATE farabi_dataset_import_jobs SET status='SUCCEEDED',lease_owner=NULL,lease_token=NULL,lease_until=NULL,updated_at=? WHERE id=? AND status='RUNNING' AND lease_token=?",id,token,now,null,null);
    }

    @Override
    public boolean fail(UUID id, UUID token, String code, String message, Instant now) {
        if(code==null||code.isBlank())throw new IllegalArgumentException("errorCode required");
        String sql="UPDATE farabi_dataset_import_jobs SET status=CASE WHEN attempts < max_attempts THEN 'QUEUED' ELSE 'FAILED' END,lease_owner=NULL,lease_token=NULL,lease_until=NULL,error_code=?,error_message=?,updated_at=? WHERE id=? AND status='RUNNING' AND lease_token=?";
        try(Connection c=source.getConnection();PreparedStatement p=c.prepareStatement(sql)){
            p.setString(1,code);p.setString(2,truncate(message,2000));p.setTimestamp(3,Timestamp.from(now));
            p.setObject(4,id);p.setObject(5,token);return p.executeUpdate()==1;
        }catch(SQLException e){throw new IllegalStateException("fail import job failed",e);}
    }

    private boolean fencedUpdate(String sql,UUID id,UUID token,Instant now,String unused1,String unused2){
        try(Connection c=source.getConnection();PreparedStatement p=c.prepareStatement(sql)){
            p.setTimestamp(1,Timestamp.from(now));p.setObject(2,id);p.setObject(3,token);return p.executeUpdate()==1;
        }catch(SQLException e){throw new IllegalStateException("complete import job failed",e);}
    }

    private static Job read(ResultSet r)throws SQLException{
        Timestamp until=r.getTimestamp("lease_until"),created=r.getTimestamp("created_at"),updated=r.getTimestamp("updated_at");
        return new Job(r.getObject("id",UUID.class),r.getString("workspace_id"),r.getObject("dataset_id",UUID.class),
            r.getString("idempotency_key"),Status.valueOf(r.getString("status")),r.getInt("attempts"),r.getInt("max_attempts"),
            r.getString("lease_owner"),r.getObject("lease_token",UUID.class),until==null?null:until.toInstant(),
            r.getString("error_code"),r.getString("error_message"),created.toInstant(),updated.toInstant());
    }
    private static String truncate(String value,int max){if(value==null)return null;return value.length()<=max?value:value.substring(0,max);}
}
