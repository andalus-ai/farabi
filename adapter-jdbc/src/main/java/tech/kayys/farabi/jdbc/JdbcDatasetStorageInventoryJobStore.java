package tech.kayys.farabi.jdbc;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import tech.kayys.farabi.engine.DatasetStorageInventoryJobPorts;
import tech.kayys.farabi.engine.DatasetStorageInventoryJobPorts.Job;
import tech.kayys.farabi.engine.DatasetStorageInventoryJobPorts.Lease;
import tech.kayys.farabi.engine.DatasetStorageInventoryJobPorts.Status;

/** PostgreSQL queue store with atomic SKIP LOCKED claims and lease-token fencing. */
public final class JdbcDatasetStorageInventoryJobStore implements DatasetStorageInventoryJobPorts.Store {
    private final DataSource source;
    public JdbcDatasetStorageInventoryJobStore(DataSource source) { this.source = Objects.requireNonNull(source); }

    @Override public Job enqueue(String workspace, String key, int maxAttempts, Instant now) {
        if (workspace == null || workspace.isBlank() || key == null || key.isBlank() || key.length() > 200 || maxAttempts < 1)
            throw new IllegalArgumentException("invalid inventory job request");
        String sql = "INSERT INTO farabi_storage_inventory_jobs(id,workspace_id,idempotency_key,status,attempts,max_attempts,available_at,created_at,updated_at) VALUES(?,?,?,'QUEUED',0,?,?,?,?) ON CONFLICT(workspace_id,idempotency_key) DO NOTHING";
        try (Connection c=source.getConnection(); PreparedStatement p=c.prepareStatement(sql)) {
            UUID id=UUID.randomUUID(); p.setObject(1,id); p.setString(2,workspace); p.setString(3,key);
            p.setInt(4,maxAttempts); p.setTimestamp(5,Timestamp.from(now)); p.setTimestamp(6,Timestamp.from(now)); p.setTimestamp(7,Timestamp.from(now)); p.executeUpdate();
            return findByKey(c,workspace,key).orElseThrow();
        } catch(SQLException e) { throw new IllegalStateException("enqueue storage inventory job failed",e); }
    }
    @Override public Optional<Job> find(UUID id,String workspace) {
        try(Connection c=source.getConnection(); PreparedStatement p=c.prepareStatement("SELECT * FROM farabi_storage_inventory_jobs WHERE id=? AND workspace_id=?")) {
            p.setObject(1,id); p.setString(2,workspace); try(ResultSet r=p.executeQuery()){return r.next()?Optional.of(read(r)):Optional.empty();}
        } catch(SQLException e){throw new IllegalStateException("read storage inventory job failed",e);}
    }
    private Optional<Job> findByKey(Connection c,String workspace,String key)throws SQLException {
        try(PreparedStatement p=c.prepareStatement("SELECT * FROM farabi_storage_inventory_jobs WHERE workspace_id=? AND idempotency_key=?")) {
            p.setString(1,workspace);p.setString(2,key);try(ResultSet r=p.executeQuery()){return r.next()?Optional.of(read(r)):Optional.empty();}
        }
    }
    @Override public Optional<Job> claim(Lease lease) {
        String select="SELECT id FROM farabi_storage_inventory_jobs WHERE (status='QUEUED' AND available_at<=?) OR (status='RUNNING' AND lease_until<=?) ORDER BY available_at,created_at FOR UPDATE SKIP LOCKED LIMIT 1";
        try(Connection c=source.getConnection()){c.setAutoCommit(false);try{
            UUID id=null;try(PreparedStatement p=c.prepareStatement(select)){p.setTimestamp(1,Timestamp.from(lease.now()));p.setTimestamp(2,Timestamp.from(lease.now()));try(ResultSet r=p.executeQuery()){if(r.next())id=r.getObject(1,UUID.class);}}
            if(id==null){c.commit();return Optional.empty();}
            UUID token=UUID.randomUUID();
            try(PreparedStatement p=c.prepareStatement("UPDATE farabi_storage_inventory_jobs SET status='RUNNING',attempts=attempts+1,lease_owner=?,lease_token=?,lease_until=?,updated_at=? WHERE id=?")){
                p.setString(1,lease.workerId());p.setObject(2,token);p.setTimestamp(3,Timestamp.from(lease.until()));p.setTimestamp(4,Timestamp.from(lease.now()));p.setObject(5,id);p.executeUpdate();}
            Optional<Job> job;try(PreparedStatement p=c.prepareStatement("SELECT * FROM farabi_storage_inventory_jobs WHERE id=?")){p.setObject(1,id);try(ResultSet r=p.executeQuery()){job=r.next()?Optional.of(read(r)):Optional.empty();}}
            c.commit();return job;
        }catch(Exception e){c.rollback();if(e instanceof RuntimeException x)throw x;throw new IllegalStateException(e);}finally{c.setAutoCommit(true);}}
        catch(SQLException e){throw new IllegalStateException("claim storage inventory job failed",e);}
    }
    @Override public boolean complete(UUID id,UUID token,UUID reportId,Instant now) {
        return update("UPDATE farabi_storage_inventory_jobs SET status='SUCCEEDED',report_id=?,lease_owner=NULL,lease_token=NULL,lease_until=NULL,updated_at=? WHERE id=? AND status='RUNNING' AND lease_token=?",
                p->{p.setObject(1,reportId);p.setTimestamp(2,Timestamp.from(now));p.setObject(3,id);p.setObject(4,token);});
    }
    @Override public boolean fail(UUID id,UUID token,String code,String message,Instant retryAt,Instant now) {
        String sql="UPDATE farabi_storage_inventory_jobs SET status=CASE WHEN attempts>=max_attempts THEN 'FAILED' ELSE 'QUEUED' END,available_at=?,error_code=?,error_message=?,lease_owner=NULL,lease_token=NULL,lease_until=NULL,updated_at=? WHERE id=? AND status='RUNNING' AND lease_token=?";
        return update(sql,p->{p.setTimestamp(1,Timestamp.from(retryAt));p.setString(2,code);p.setString(3,message);p.setTimestamp(4,Timestamp.from(now));p.setObject(5,id);p.setObject(6,token);});
    }
    private boolean update(String sql, Binder binder){try(Connection c=source.getConnection();PreparedStatement p=c.prepareStatement(sql)){binder.bind(p);return p.executeUpdate()==1;}catch(SQLException e){throw new IllegalStateException("update storage inventory job failed",e);}}
    private Job read(ResultSet r)throws SQLException {
        Timestamp until=r.getTimestamp("lease_until");
        return new Job(r.getObject("id",UUID.class),r.getString("workspace_id"),r.getString("idempotency_key"),
                Status.valueOf(r.getString("status")),r.getInt("attempts"),r.getInt("max_attempts"),r.getString("lease_owner"),
                r.getObject("lease_token",UUID.class),until==null?null:until.toInstant(),r.getTimestamp("available_at").toInstant(),
                r.getObject("report_id",UUID.class),r.getString("error_code"),r.getString("error_message"),
                r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant());
    }
    @FunctionalInterface private interface Binder { void bind(PreparedStatement p)throws SQLException; }
}
