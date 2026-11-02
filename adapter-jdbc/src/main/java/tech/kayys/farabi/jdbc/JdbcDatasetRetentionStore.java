package tech.kayys.farabi.jdbc;

import java.sql.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import javax.sql.DataSource;

/** JDBC implementation for versioned retention policies, exemptions and read-only eligibility previews. */
public final class JdbcDatasetRetentionStore {
    public record Policy(UUID id,String workspaceId,String sourceId,int retentionDays,Instant effectiveAt,
            String createdBy,Instant createdAt,UUID supersedesPolicyId) {}
    public record Exemption(UUID id,String workspaceId,UUID datasetId,String reason,String createdBy,
            Instant createdAt,Instant expiresAt,String revokedBy,Instant revokedAt,String revokeReason) {}
    public record Candidate(UUID datasetId,String name,String sourceId,Instant createdAt,int retentionDays,
            Instant eligibleAt,boolean legalHold) {}
    private final DataSource source;
    public JdbcDatasetRetentionStore(DataSource source){this.source=Objects.requireNonNull(source);}

    public Policy createPolicy(String workspace,String sourceId,int days,Instant effectiveAt,String actor,String reason,Instant now){
        required(workspace,"workspace");required(actor,"actor");required(reason,"reason");
        if(days<1||days>36500)throw new IllegalArgumentException("retentionDays must be 1..36500");
        try(Connection c=source.getConnection()){
            c.setAutoCommit(false);
            try{
                UUID previous=null;
                String q="SELECT id FROM farabi_dataset_retention_policies WHERE workspace_id=? AND source_id IS NOT DISTINCT FROM ? AND effective_at<=? ORDER BY effective_at DESC,created_at DESC LIMIT 1 FOR UPDATE";
                try(PreparedStatement p=c.prepareStatement(q)){p.setString(1,workspace);p.setString(2,sourceId);p.setTimestamp(3,Timestamp.from(effectiveAt));try(ResultSet r=p.executeQuery()){if(r.next())previous=r.getObject(1,UUID.class);}}
                UUID id=UUID.randomUUID();
                try(PreparedStatement p=c.prepareStatement("INSERT INTO farabi_dataset_retention_policies(id,workspace_id,source_id,retention_days,effective_at,created_by,created_at,supersedes_policy_id) VALUES(?,?,?,?,?,?,?,?)")){
                    p.setObject(1,id);p.setString(2,workspace);p.setString(3,sourceId);p.setInt(4,days);p.setTimestamp(5,Timestamp.from(effectiveAt));p.setString(6,actor);p.setTimestamp(7,Timestamp.from(now));p.setObject(8,previous);p.executeUpdate();
                }
                audit(c,workspace,id,null,null,"POLICY_CREATED",actor,reason,now);c.commit();
                return new Policy(id,workspace,sourceId,days,effectiveAt,actor,now,previous);
            }catch(Exception e){c.rollback();if(e instanceof RuntimeException re)throw re;throw new IllegalStateException("create retention policy failed",e);}
            finally{c.setAutoCommit(true);}
        }catch(SQLException e){throw new IllegalStateException("create retention policy failed",e);}
    }

    public Exemption createExemption(UUID dataset,String workspace,String actor,String reason,Instant expiresAt,Instant now){
        required(workspace,"workspace");required(actor,"actor");required(reason,"reason");
        if(expiresAt!=null&&!expiresAt.isAfter(now))throw new IllegalArgumentException("expiresAt must be in the future");
        try(Connection c=source.getConnection()){
            c.setAutoCommit(false);
            try{
                try(PreparedStatement p=c.prepareStatement("SELECT 1 FROM farabi_datasets WHERE id=? AND workspace_id=? FOR UPDATE")){
                    p.setObject(1,dataset);p.setString(2,workspace);try(ResultSet r=p.executeQuery()){if(!r.next())throw new NoSuchElementException("dataset not found");}
                }
                UUID id=UUID.randomUUID();
                try(PreparedStatement p=c.prepareStatement("INSERT INTO farabi_dataset_retention_exemptions(id,workspace_id,dataset_id,reason,created_by,created_at,expires_at) VALUES(?,?,?,?,?,?,?)")){
                    p.setObject(1,id);p.setString(2,workspace);p.setObject(3,dataset);p.setString(4,reason);p.setString(5,actor);p.setTimestamp(6,Timestamp.from(now));p.setTimestamp(7,expiresAt==null?null:Timestamp.from(expiresAt));p.executeUpdate();
                }
                audit(c,workspace,null,dataset,id,"EXEMPTION_CREATED",actor,reason,now);c.commit();
                return new Exemption(id,workspace,dataset,reason,actor,now,expiresAt,null,null,null);
            }catch(Exception e){c.rollback();if(e instanceof RuntimeException re)throw re;throw new IllegalStateException("create retention exemption failed",e);}
            finally{c.setAutoCommit(true);}
        }catch(SQLException e){throw new IllegalStateException("create retention exemption failed",e);}
    }

    public Exemption revokeExemption(UUID id,String workspace,String actor,String reason,Instant now){
        required(actor,"actor");required(reason,"reason");
        try(Connection c=source.getConnection()){
            c.setAutoCommit(false);
            try{
                Exemption e;
                try(PreparedStatement p=c.prepareStatement("SELECT * FROM farabi_dataset_retention_exemptions WHERE id=? AND workspace_id=? FOR UPDATE")){
                    p.setObject(1,id);p.setString(2,workspace);try(ResultSet r=p.executeQuery()){if(!r.next())throw new NoSuchElementException("exemption not found");e=readExemption(r);}
                }
                if(e.revokedAt()!=null){c.commit();return e;}
                try(PreparedStatement p=c.prepareStatement("UPDATE farabi_dataset_retention_exemptions SET revoked_by=?,revoked_at=?,revoke_reason=? WHERE id=? AND workspace_id=? AND revoked_at IS NULL")){
                    p.setString(1,actor);p.setTimestamp(2,Timestamp.from(now));p.setString(3,reason);p.setObject(4,id);p.setString(5,workspace);p.executeUpdate();
                }
                audit(c,workspace,null,e.datasetId(),id,"EXEMPTION_REVOKED",actor,reason,now);c.commit();
                return new Exemption(e.id(),e.workspaceId(),e.datasetId(),e.reason(),e.createdBy(),e.createdAt(),e.expiresAt(),actor,now,reason);
            }catch(Exception ex){c.rollback();if(ex instanceof RuntimeException re)throw re;throw new IllegalStateException("revoke retention exemption failed",ex);}
            finally{c.setAutoCommit(true);}
        }catch(SQLException e){throw new IllegalStateException("revoke retention exemption failed",e);}
    }

    public List<Candidate> preview(String workspace,Instant now,int limit){
        if(limit<1||limit>500)throw new IllegalArgumentException("limit must be 1..500");
        String sql="SELECT d.id,d.name,d.source_id,d.created_at,p.retention_days,(d.created_at + (p.retention_days * INTERVAL '1 day')) AS eligible_at, EXISTS(SELECT 1 FROM farabi_dataset_legal_holds h WHERE h.dataset_id=d.id AND h.workspace_id=d.workspace_id AND h.released_at IS NULL) AS held FROM farabi_datasets d JOIN LATERAL (SELECT rp.retention_days FROM farabi_dataset_retention_policies rp WHERE rp.workspace_id=d.workspace_id AND (rp.source_id=d.source_id OR rp.source_id IS NULL) AND rp.effective_at<=? ORDER BY CASE WHEN rp.source_id=d.source_id THEN 0 ELSE 1 END,rp.effective_at DESC,rp.created_at DESC LIMIT 1) p ON TRUE WHERE d.workspace_id=? AND d.lifecycle NOT IN ('DELETE_PENDING','ARCHIVED') AND d.created_at + (p.retention_days * INTERVAL '1 day') <= ? AND NOT EXISTS(SELECT 1 FROM farabi_dataset_retention_exemptions e WHERE e.dataset_id=d.id AND e.workspace_id=d.workspace_id AND e.revoked_at IS NULL AND (e.expires_at IS NULL OR e.expires_at>?)) ORDER BY eligible_at,d.id LIMIT ?";
        try(Connection c=source.getConnection();PreparedStatement p=c.prepareStatement(sql)){
            p.setTimestamp(1,Timestamp.from(now));p.setString(2,workspace);p.setTimestamp(3,Timestamp.from(now));p.setTimestamp(4,Timestamp.from(now));p.setInt(5,limit);
            try(ResultSet r=p.executeQuery()){List<Candidate> out=new ArrayList<>();while(r.next())out.add(new Candidate(r.getObject("id",UUID.class),r.getString("name"),r.getString("source_id"),r.getTimestamp("created_at").toInstant(),r.getInt("retention_days"),r.getTimestamp("eligible_at").toInstant(),r.getBoolean("held")));return List.copyOf(out);}
        }catch(SQLException e){throw new IllegalStateException("retention preview failed",e);}
    }

    public List<Policy> policies(String workspace,int limit){
        if(limit<1||limit>100)throw new IllegalArgumentException("limit must be 1..100");
        try(Connection c=source.getConnection();PreparedStatement p=c.prepareStatement("SELECT * FROM farabi_dataset_retention_policies WHERE workspace_id=? ORDER BY created_at DESC LIMIT ?")){
            p.setString(1,workspace);p.setInt(2,limit);try(ResultSet r=p.executeQuery()){List<Policy> out=new ArrayList<>();while(r.next())out.add(new Policy(r.getObject("id",UUID.class),workspace,r.getString("source_id"),r.getInt("retention_days"),r.getTimestamp("effective_at").toInstant(),r.getString("created_by"),r.getTimestamp("created_at").toInstant(),r.getObject("supersedes_policy_id",UUID.class)));return List.copyOf(out);}
        }catch(SQLException e){throw new IllegalStateException("read retention policies failed",e);}
    }

    private static void audit(Connection c,String ws,UUID policy,UUID dataset,UUID exemption,String type,String actor,String reason,Instant now)throws SQLException{
        try(PreparedStatement p=c.prepareStatement("INSERT INTO farabi_dataset_retention_audit(id,workspace_id,policy_id,dataset_id,exemption_id,event_type,actor_id,reason,occurred_at) VALUES(?,?,?,?,?,?,?,?,?)")){
            p.setObject(1,UUID.randomUUID());p.setString(2,ws);p.setObject(3,policy);p.setObject(4,dataset);p.setObject(5,exemption);p.setString(6,type);p.setString(7,actor);p.setString(8,reason);p.setTimestamp(9,Timestamp.from(now));p.executeUpdate();
        }
    }
    private static Exemption readExemption(ResultSet r)throws SQLException{Timestamp exp=r.getTimestamp("expires_at"),rev=r.getTimestamp("revoked_at");return new Exemption(r.getObject("id",UUID.class),r.getString("workspace_id"),r.getObject("dataset_id",UUID.class),r.getString("reason"),r.getString("created_by"),r.getTimestamp("created_at").toInstant(),exp==null?null:exp.toInstant(),r.getString("revoked_by"),rev==null?null:rev.toInstant(),r.getString("revoke_reason"));}
    private static void required(String s,String n){if(s==null||s.isBlank())throw new IllegalArgumentException(n+" required");}
}
