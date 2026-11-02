package tech.kayys.farabi.jdbc;

import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;

/** Transactional maker-checker workflow; only approved records can create deletion jobs. */
public final class JdbcDatasetDeletionApprovalStore {
    public record Approval(UUID id,String workspaceId,UUID datasetId,String requesterId,String requestReason,
            Instant requestedAt,Instant expiresAt,String status,String approverId,String decisionReason,
            Instant decidedAt,UUID deletionJobId) {}
    public record AuditEvent(UUID id,UUID approvalId,UUID datasetId,String eventType,String actorId,String reason,Instant occurredAt) {}
    private final DataSource source;
    public JdbcDatasetDeletionApprovalStore(DataSource source){this.source=Objects.requireNonNull(source);}

    public Approval request(UUID dataset,String workspace,String requester,String reason,Instant now,Duration lifetime){
        required(workspace,"workspace");required(requester,"requester");required(reason,"reason");
        if(lifetime==null||lifetime.isNegative()||lifetime.isZero()||lifetime.compareTo(Duration.ofDays(30))>0)
            throw new IllegalArgumentException("approval lifetime must be positive and at most 30 days");
        try(Connection c=source.getConnection()){
            c.setAutoCommit(false);
            try{
                try(PreparedStatement p=c.prepareStatement("SELECT lifecycle FROM farabi_datasets WHERE id=? AND workspace_id=? FOR UPDATE")){
                    p.setObject(1,dataset);p.setString(2,workspace);
                    try(ResultSet r=p.executeQuery()){if(!r.next())throw new NoSuchElementException("dataset not found");if("ARCHIVED".equals(r.getString(1)))throw new IllegalStateException("dataset is already archived");}
                }
                expirePendingForDataset(c,dataset,workspace,now);
                try(PreparedStatement p=c.prepareStatement("SELECT 1 FROM farabi_dataset_deletion_approvals WHERE dataset_id=? AND workspace_id=? AND status IN ('PENDING','APPROVED') LIMIT 1")){
                    p.setObject(1,dataset);p.setString(2,workspace);try(ResultSet r=p.executeQuery()){if(r.next())throw new IllegalStateException("dataset already has a pending or approved deletion request");}
                }
                UUID id=UUID.randomUUID();Instant expires=now.plus(lifetime);
                try(PreparedStatement p=c.prepareStatement("INSERT INTO farabi_dataset_deletion_approvals(id,workspace_id,dataset_id,requester_id,request_reason,requested_at,expires_at,status) VALUES(?,?,?,?,?,?,?,'PENDING')")){
                    p.setObject(1,id);p.setString(2,workspace);p.setObject(3,dataset);p.setString(4,requester);p.setString(5,reason);p.setTimestamp(6,Timestamp.from(now));p.setTimestamp(7,Timestamp.from(expires));p.executeUpdate();
                }
                audit(c,id,workspace,dataset,"REQUESTED",requester,reason,now);c.commit();
                return new Approval(id,workspace,dataset,requester,reason,now,expires,"PENDING",null,null,null,null);
            }catch(Exception e){c.rollback();if(e instanceof RuntimeException re)throw re;throw new IllegalStateException("request deletion approval failed",e);}
            finally{c.setAutoCommit(true);}
        }catch(SQLException e){throw new IllegalStateException("request deletion approval failed",e);}
    }

    public Approval approve(UUID id,String workspace,String approver,String reason,Instant now,int maxAttempts){
        required(approver,"approver");required(reason,"reason");
        if(maxAttempts<1)throw new IllegalArgumentException("maxAttempts must be positive");
        try(Connection c=source.getConnection()){
            c.setAutoCommit(false);
            try{
                Approval snapshot=readLocked(c,id,workspace,false);
                lockDataset(c,snapshot.datasetId(),workspace);
                Approval a=readLocked(c,id,workspace,true);
                if(a.status().equals("APPROVED")){c.commit();return a;}
                if(!a.status().equals("PENDING"))throw new IllegalStateException("approval is not pending");
                if(a.requesterId().equals(approver))throw new SecurityException("requester cannot approve their own deletion");
                try(PreparedStatement p=c.prepareStatement("SELECT location_ref,source_id,created_at,lifecycle FROM farabi_datasets WHERE id=? AND workspace_id=? FOR UPDATE")){
                    p.setObject(1,a.datasetId());p.setString(2,workspace);
                    try(ResultSet r=p.executeQuery()){if(!r.next())throw new NoSuchElementException("dataset not found");}
                }
                if(!a.expiresAt().isAfter(now)){
                    updateDecision(c,id,workspace,"EXPIRED",approver,"approval expired",now,null);
                    audit(c,id,workspace,a.datasetId(),"EXPIRED",approver,"approval expired",now);c.commit();
                    throw new ApprovalExpiredException("deletion approval expired");
                }
                enforceEligible(c,a.datasetId(),workspace,now);
                String location;
                try(PreparedStatement p=c.prepareStatement("SELECT location_ref FROM farabi_datasets WHERE id=? AND workspace_id=?")){
                    p.setObject(1,a.datasetId());p.setString(2,workspace);try(ResultSet r=p.executeQuery()){r.next();location=r.getString(1);}
                }
                UUID job=UUID.randomUUID();
                try(PreparedStatement p=c.prepareStatement("INSERT INTO farabi_dataset_deletions(id,workspace_id,dataset_id,location_ref,status,attempts,max_attempts,available_at,created_at,updated_at,approval_id) VALUES(?,?,?,?,'QUEUED',0,?,?,?, ?,?)")){
                    p.setObject(1,job);p.setString(2,workspace);p.setObject(3,a.datasetId());p.setString(4,location);p.setInt(5,maxAttempts);p.setTimestamp(6,Timestamp.from(now));p.setTimestamp(7,Timestamp.from(now));p.setTimestamp(8,Timestamp.from(now));p.setObject(9,id);p.executeUpdate();
                }
                try(PreparedStatement p=c.prepareStatement("UPDATE farabi_datasets SET lifecycle='DELETE_PENDING',deletion_requested_at=?,updated_at=? WHERE id=? AND workspace_id=?")){
                    p.setTimestamp(1,Timestamp.from(now));p.setTimestamp(2,Timestamp.from(now));p.setObject(3,a.datasetId());p.setString(4,workspace);p.executeUpdate();
                }
                updateDecision(c,id,workspace,"APPROVED",approver,reason,now,job);
                audit(c,id,workspace,a.datasetId(),"APPROVED",approver,reason,now);
                c.commit();
                return new Approval(a.id(),workspace,a.datasetId(),a.requesterId(),a.requestReason(),a.requestedAt(),a.expiresAt(),"APPROVED",approver,reason,now,job);
            }catch(Exception e){c.rollback();if(e instanceof RuntimeException re)throw re;throw new IllegalStateException("approve deletion failed",e);}
            finally{c.setAutoCommit(true);}
        }catch(SQLException e){throw new IllegalStateException("approve deletion failed",e);}
    }

    public Approval reject(UUID id,String workspace,String approver,String reason,Instant now){
        required(approver,"approver");required(reason,"reason");
        try(Connection c=source.getConnection()){
            c.setAutoCommit(false);
            try{
                Approval snapshot=readLocked(c,id,workspace,false);
                lockDataset(c,snapshot.datasetId(),workspace);
                Approval a=readLocked(c,id,workspace,true);
                if(!a.status().equals("PENDING"))throw new IllegalStateException("approval is not pending");
                if(a.requesterId().equals(approver))throw new SecurityException("requester cannot decide their own deletion");
                if(!a.expiresAt().isAfter(now)){
                    updateDecision(c,id,workspace,"EXPIRED","system:expiry","approval expired",now,null);
                    audit(c,id,workspace,a.datasetId(),"EXPIRED","system:expiry","approval expired",now);c.commit();
                    return new Approval(a.id(),workspace,a.datasetId(),a.requesterId(),a.requestReason(),a.requestedAt(),a.expiresAt(),"EXPIRED","system:expiry","approval expired",now,null);
                }
                updateDecision(c,id,workspace,"REJECTED",approver,reason,now,null);
                audit(c,id,workspace,a.datasetId(),"REJECTED",approver,reason,now);c.commit();
                return new Approval(a.id(),workspace,a.datasetId(),a.requesterId(),a.requestReason(),a.requestedAt(),a.expiresAt(),"REJECTED",approver,reason,now,null);
            }catch(Exception e){c.rollback();if(e instanceof RuntimeException re)throw re;throw new IllegalStateException("reject deletion failed",e);}
            finally{c.setAutoCommit(true);}
        }catch(SQLException e){throw new IllegalStateException("reject deletion failed",e);}
    }

    public Optional<Approval> find(UUID id,String workspace){
        try(Connection c=source.getConnection();PreparedStatement p=c.prepareStatement("SELECT * FROM farabi_dataset_deletion_approvals WHERE id=? AND workspace_id=?")){
            p.setObject(1,id);p.setString(2,workspace);try(ResultSet r=p.executeQuery()){return r.next()?Optional.of(read(r)):Optional.empty();}
        }catch(SQLException e){throw new IllegalStateException("read deletion approval failed",e);}
    }
    public List<Approval> recent(String workspace,int limit){
        if(limit<1||limit>100)throw new IllegalArgumentException("limit must be 1..100");
        try(Connection c=source.getConnection();PreparedStatement p=c.prepareStatement("SELECT * FROM farabi_dataset_deletion_approvals WHERE workspace_id=? ORDER BY requested_at DESC LIMIT ?")){
            p.setString(1,workspace);p.setInt(2,limit);try(ResultSet r=p.executeQuery()){List<Approval> out=new ArrayList<>();while(r.next())out.add(read(r));return List.copyOf(out);}
        }catch(SQLException e){throw new IllegalStateException("list deletion approvals failed",e);}
    }


    public List<AuditEvent> audit(UUID approvalId,String workspace){
        String sql="SELECT a.id,a.approval_id,a.dataset_id,a.event_type,a.actor_id,a.reason,a.occurred_at FROM farabi_dataset_deletion_approval_audit a JOIN farabi_dataset_deletion_approvals p ON p.id=a.approval_id AND p.workspace_id=a.workspace_id WHERE a.approval_id=? AND a.workspace_id=? ORDER BY a.occurred_at,a.id";
        try(Connection c=source.getConnection();PreparedStatement p=c.prepareStatement(sql)){
            p.setObject(1,approvalId);p.setString(2,workspace);
            try(ResultSet r=p.executeQuery()){List<AuditEvent> out=new ArrayList<>();while(r.next())out.add(new AuditEvent(r.getObject("id",UUID.class),r.getObject("approval_id",UUID.class),r.getObject("dataset_id",UUID.class),r.getString("event_type"),r.getString("actor_id"),r.getString("reason"),r.getTimestamp("occurred_at").toInstant()));return List.copyOf(out);}
        }catch(SQLException e){throw new IllegalStateException("read deletion approval audit failed",e);}
    }

    private static void expirePendingForDataset(Connection c,UUID dataset,String ws,Instant now)throws SQLException{
        List<UUID> expired=new ArrayList<>();
        try(PreparedStatement p=c.prepareStatement("SELECT id FROM farabi_dataset_deletion_approvals WHERE dataset_id=? AND workspace_id=? AND status='PENDING' AND expires_at<=? FOR UPDATE")){
            p.setObject(1,dataset);p.setString(2,ws);p.setTimestamp(3,Timestamp.from(now));try(ResultSet r=p.executeQuery()){while(r.next())expired.add(r.getObject(1,UUID.class));}
        }
        for(UUID id:expired){
            updateDecision(c,id,ws,"EXPIRED","system:expiry","approval expired",now,null);
            audit(c,id,ws,dataset,"EXPIRED","system:expiry","approval expired",now);
        }
    }
    private static void enforceEligible(Connection c,UUID dataset,String ws,Instant now)throws SQLException{
        try(PreparedStatement p=c.prepareStatement("SELECT 1 FROM farabi_dataset_legal_holds WHERE dataset_id=? AND workspace_id=? AND released_at IS NULL LIMIT 1")){
            p.setObject(1,dataset);p.setString(2,ws);try(ResultSet r=p.executeQuery()){if(r.next())throw new IllegalStateException("dataset is under an active legal hold");}
        }
        String q="SELECT d.source_id,d.created_at FROM farabi_datasets d WHERE d.id=? AND d.workspace_id=?";
        String source;Instant created;
        try(PreparedStatement p=c.prepareStatement(q)){p.setObject(1,dataset);p.setString(2,ws);try(ResultSet r=p.executeQuery()){r.next();source=r.getString(1);created=r.getTimestamp(2).toInstant();}}
        boolean exempt=false;
        try(PreparedStatement p=c.prepareStatement("SELECT 1 FROM farabi_dataset_retention_exemptions WHERE dataset_id=? AND workspace_id=? AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at>?) LIMIT 1")){
            p.setObject(1,dataset);p.setString(2,ws);p.setTimestamp(3,Timestamp.from(now));try(ResultSet r=p.executeQuery()){exempt=r.next();}
        }
        if(!exempt){
            Integer days=null;
            try(PreparedStatement p=c.prepareStatement("SELECT retention_days FROM farabi_dataset_retention_policies WHERE workspace_id=? AND (source_id=? OR source_id IS NULL) AND effective_at<=? ORDER BY CASE WHEN source_id=? THEN 0 ELSE 1 END,effective_at DESC,created_at DESC LIMIT 1")){
                p.setString(1,ws);p.setString(2,source);p.setTimestamp(3,Timestamp.from(now));p.setString(4,source);try(ResultSet r=p.executeQuery()){if(r.next())days=r.getInt(1);}
            }
            if(days==null||created.plus(Duration.ofDays(days)).isAfter(now))throw new IllegalStateException("retention eligibility is not satisfied");
        }
    }
    private static void lockDataset(Connection c,UUID dataset,String ws)throws SQLException{
        try(PreparedStatement p=c.prepareStatement("SELECT 1 FROM farabi_datasets WHERE id=? AND workspace_id=? FOR UPDATE")){
            p.setObject(1,dataset);p.setString(2,ws);try(ResultSet r=p.executeQuery()){if(!r.next())throw new NoSuchElementException("dataset not found");}
        }
    }
    private static Approval readLocked(Connection c,UUID id,String ws,boolean lock)throws SQLException{
        try(PreparedStatement p=c.prepareStatement("SELECT * FROM farabi_dataset_deletion_approvals WHERE id=? AND workspace_id=?"+(lock?" FOR UPDATE":""))){
            p.setObject(1,id);p.setString(2,ws);try(ResultSet r=p.executeQuery()){if(!r.next())throw new NoSuchElementException("deletion approval not found");return read(r);}
        }
    }
    private static Approval read(ResultSet r)throws SQLException{
        Timestamp decided=r.getTimestamp("decided_at");
        return new Approval(r.getObject("id",UUID.class),r.getString("workspace_id"),r.getObject("dataset_id",UUID.class),r.getString("requester_id"),r.getString("request_reason"),r.getTimestamp("requested_at").toInstant(),r.getTimestamp("expires_at").toInstant(),r.getString("status"),r.getString("approver_id"),r.getString("decision_reason"),decided==null?null:decided.toInstant(),r.getObject("deletion_job_id",UUID.class));
    }
    private static void updateDecision(Connection c,UUID id,String ws,String status,String actor,String reason,Instant now,UUID job)throws SQLException{
        try(PreparedStatement p=c.prepareStatement("UPDATE farabi_dataset_deletion_approvals SET status=?,approver_id=?,decision_reason=?,decided_at=?,deletion_job_id=? WHERE id=? AND workspace_id=? AND status='PENDING'")){
            p.setString(1,status);p.setString(2,actor);p.setString(3,reason);p.setTimestamp(4,Timestamp.from(now));p.setObject(5,job);p.setObject(6,id);p.setString(7,ws);if(p.executeUpdate()!=1)throw new IllegalStateException("approval changed concurrently");
        }
    }
    private static void audit(Connection c,UUID id,String ws,UUID dataset,String type,String actor,String reason,Instant now)throws SQLException{
        try(PreparedStatement p=c.prepareStatement("INSERT INTO farabi_dataset_deletion_approval_audit(id,approval_id,workspace_id,dataset_id,event_type,actor_id,reason,occurred_at) VALUES(?,?,?,?,?,?,?,?)")){
            p.setObject(1,UUID.randomUUID());p.setObject(2,id);p.setString(3,ws);p.setObject(4,dataset);p.setString(5,type);p.setString(6,actor);p.setString(7,reason);p.setTimestamp(8,Timestamp.from(now));p.executeUpdate();
        }
    }
    private static void required(String s,String n){if(s==null||s.isBlank())throw new IllegalArgumentException(n+" required");}
    public static final class ApprovalExpiredException extends IllegalStateException{public ApprovalExpiredException(String m){super(m);}}
}
