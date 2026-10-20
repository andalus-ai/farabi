package tech.kayys.farabi.jdbc;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import tech.kayys.farabi.engine.QueryContracts.Status;
import tech.kayys.farabi.engine.WorkerLeases.*;
public final class JdbcWorkerLeaseRepository implements Repository{
 private final DataSource source;
 public JdbcWorkerLeaseRepository(DataSource source){this.source=Objects.requireNonNull(source);}
 public Optional<Lease> claim(UUID id,String ws,String worker,Instant now,Duration ttl){
  String sql="UPDATE farabi_query_executions SET lease_owner=?,lease_fence=lease_fence+1,lease_expires_at=? WHERE id=? AND workspace_id=? AND status IN ('SUBMITTED','RUNNING') AND (lease_expires_at IS NULL OR lease_expires_at<=?) RETURNING lease_fence";
  try(Connection c=source.getConnection();PreparedStatement p=c.prepareStatement(sql)){
   p.setString(1,worker);p.setTimestamp(2,Timestamp.from(now.plus(ttl)));p.setObject(3,id);p.setString(4,ws);p.setTimestamp(5,Timestamp.from(now));
   try(ResultSet r=p.executeQuery()){return r.next()?Optional.of(new Lease(id,ws,worker,r.getLong(1),now.plus(ttl))):Optional.empty();}
  }catch(SQLException e){throw new IllegalStateException("lease claim failed",e);}
 }
 public boolean renew(Lease lease,Instant now,Duration ttl){
  String sql="UPDATE farabi_query_executions SET lease_expires_at=? WHERE id=? AND workspace_id=? AND lease_owner=? AND lease_fence=? AND lease_expires_at>? AND status IN ('SUBMITTED','RUNNING')";
  try(Connection c=source.getConnection();PreparedStatement p=c.prepareStatement(sql)){
   p.setTimestamp(1,Timestamp.from(now.plus(ttl)));p.setObject(2,lease.executionId());p.setString(3,lease.workspaceId());p.setString(4,lease.workerId());p.setLong(5,lease.fence());p.setTimestamp(6,Timestamp.from(now));return p.executeUpdate()==1;
  }catch(SQLException e){throw new IllegalStateException("lease renewal failed",e);}
 }
 public boolean complete(Lease lease,Status expected,Status next,Instant now){
  String sql="UPDATE farabi_query_executions SET status=?,updated_at=?,lease_owner=NULL,lease_expires_at=NULL WHERE id=? AND workspace_id=? AND lease_owner=? AND lease_fence=? AND lease_expires_at>? AND status=?";
  try(Connection c=source.getConnection();PreparedStatement p=c.prepareStatement(sql)){
   p.setString(1,next.name());p.setTimestamp(2,Timestamp.from(now));p.setObject(3,lease.executionId());p.setString(4,lease.workspaceId());p.setString(5,lease.workerId());p.setLong(6,lease.fence());p.setTimestamp(7,Timestamp.from(now));p.setString(8,expected.name());return p.executeUpdate()==1;
  }catch(SQLException e){throw new IllegalStateException("lease completion failed",e);}
 }
}