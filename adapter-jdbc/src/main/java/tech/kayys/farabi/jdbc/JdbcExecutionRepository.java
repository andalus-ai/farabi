package tech.kayys.farabi.jdbc;

import java.sql.*;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import tech.kayys.farabi.engine.QueryContracts.*;
import tech.kayys.farabi.engine.QueryPorts.ExecutionRepository;

public final class JdbcExecutionRepository implements ExecutionRepository {
    private final DataSource dataSource;
    public JdbcExecutionRepository(DataSource dataSource) { this.dataSource=Objects.requireNonNull(dataSource); }
    @Override public void create(Execution e) {
        Objects.requireNonNull(e);
        String sql="INSERT INTO farabi_query_executions(id,workspace_id,subject_id,source_id,provider_job_id,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)";
        try(Connection c=dataSource.getConnection(); PreparedStatement p=c.prepareStatement(sql)) {
            p.setObject(1,e.id());p.setString(2,e.workspaceId());p.setString(3,e.subjectId());p.setString(4,e.sourceId());
            p.setString(5,e.providerJobId());p.setString(6,e.status().name());
            p.setTimestamp(7,Timestamp.from(e.createdAt()));p.setTimestamp(8,Timestamp.from(e.updatedAt()));p.executeUpdate();
        } catch(SQLException ex) { throw new IllegalStateException("create execution failed",ex); }
    }
    @Override public Optional<Execution> find(UUID id,String workspaceId) {
        Objects.requireNonNull(id);
        String sql="SELECT id,workspace_id,subject_id,source_id,provider_job_id,status,created_at,updated_at FROM farabi_query_executions WHERE id=? AND workspace_id=?";
        try(Connection c=dataSource.getConnection(); PreparedStatement p=c.prepareStatement(sql)) {
            p.setObject(1,id);p.setString(2,workspaceId);
            try(ResultSet r=p.executeQuery()) {
                if(!r.next()) return Optional.empty();
                return Optional.of(new Execution(r.getObject("id",UUID.class),r.getString("workspace_id"),r.getString("subject_id"),
                    r.getString("source_id"),r.getString("provider_job_id"),Status.valueOf(r.getString("status")),
                    r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant()));
            }
        } catch(SQLException ex) { throw new IllegalStateException("find execution failed",ex); }
    }
    @Override public boolean transition(UUID id,String workspaceId,Status expected,Status next,String providerJobId) {
        Objects.requireNonNull(id);Objects.requireNonNull(expected);Objects.requireNonNull(next);
        boolean allowed=switch(expected) {
            case PENDING -> next==Status.SUBMITTED||next==Status.FAILED||next==Status.CANCELLED;
            case SUBMITTED -> next==Status.RUNNING||next==Status.FAILED||next==Status.CANCELLED;
            case RUNNING -> next==Status.SUCCEEDED||next==Status.FAILED||next==Status.CANCELLED;
            case SUCCEEDED,FAILED,CANCELLED -> false;
        };
        if(!allowed) throw new IllegalArgumentException("illegal status transition");
        if(providerJobId!=null) tech.kayys.farabi.engine.QueryContracts.required(providerJobId,"providerJobId");
        String sql="UPDATE farabi_query_executions SET status=?,provider_job_id=COALESCE(?,provider_job_id),updated_at=CURRENT_TIMESTAMP WHERE id=? AND workspace_id=? AND status=?";
        try(Connection c=dataSource.getConnection();PreparedStatement p=c.prepareStatement(sql)) {
            p.setString(1,next.name());p.setString(2,providerJobId);p.setObject(3,id);p.setString(4,workspaceId);p.setString(5,expected.name());
            return p.executeUpdate()==1;
        } catch(SQLException ex) { throw new IllegalStateException("transition execution failed",ex); }
    }
}
