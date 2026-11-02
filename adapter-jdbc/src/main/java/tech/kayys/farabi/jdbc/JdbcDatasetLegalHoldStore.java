package tech.kayys.farabi.jdbc;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;

/** Workspace-scoped legal holds with immutable placement/release audit events. */
public final class JdbcDatasetLegalHoldStore {
    public record Hold(UUID id, String workspaceId, UUID datasetId, String reason,
            String placedBy, Instant placedAt, String releasedBy, Instant releasedAt, String releaseReason) {
        public boolean active() { return releasedAt == null; }
    }
    private final DataSource source;
    public JdbcDatasetLegalHoldStore(DataSource source) { this.source=Objects.requireNonNull(source); }

    public Hold place(UUID datasetId, String workspace, String actor, String reason, Instant now) {
        required(workspace,"workspace"); required(actor,"actor"); required(reason,"reason");
        try(Connection c=source.getConnection()) {
            c.setAutoCommit(false);
            try {
                try(PreparedStatement p=c.prepareStatement("SELECT 1 FROM farabi_datasets WHERE id=? AND workspace_id=? FOR UPDATE")) {
                    p.setObject(1,datasetId);p.setString(2,workspace);
                    try(ResultSet r=p.executeQuery()){if(!r.next())throw new NoSuchElementException("dataset not found");}
                }
                try(PreparedStatement p=c.prepareStatement("SELECT * FROM farabi_dataset_legal_holds WHERE dataset_id=? AND workspace_id=? AND released_at IS NULL FOR UPDATE")) {
                    p.setObject(1,datasetId);p.setString(2,workspace);
                    try(ResultSet r=p.executeQuery()){if(r.next())throw new IllegalStateException("dataset already has an active legal hold");}
                }
                UUID id=UUID.randomUUID();
                try(PreparedStatement p=c.prepareStatement("INSERT INTO farabi_dataset_legal_holds(id,workspace_id,dataset_id,reason,placed_by,placed_at) VALUES(?,?,?,?,?,?)")) {
                    p.setObject(1,id);p.setString(2,workspace);p.setObject(3,datasetId);p.setString(4,reason);p.setString(5,actor);p.setTimestamp(6,Timestamp.from(now));p.executeUpdate();
                }
                audit(c,id,workspace,datasetId,"PLACED",actor,reason,now);
                c.commit();
                return new Hold(id,workspace,datasetId,reason,actor,now,null,null,null);
            } catch(Exception e) { c.rollback(); if(e instanceof RuntimeException re)throw re; throw new IllegalStateException("place legal hold failed",e); }
            finally { c.setAutoCommit(true); }
        } catch(SQLException e) { throw new IllegalStateException("place legal hold failed",e); }
    }

    public Hold release(UUID holdId, String workspace, String actor, String reason, Instant now) {
        required(workspace,"workspace");required(actor,"actor");required(reason,"reason");
        try(Connection c=source.getConnection()) {
            c.setAutoCommit(false);
            try {
                Hold h;
                try(PreparedStatement p=c.prepareStatement("SELECT * FROM farabi_dataset_legal_holds WHERE id=? AND workspace_id=? FOR UPDATE")) {
                    p.setObject(1,holdId);p.setString(2,workspace);
                    try(ResultSet r=p.executeQuery()){if(!r.next())throw new NoSuchElementException("legal hold not found");h=read(r);}
                }
                if(!h.active()) { c.commit(); return h; }
                // Match the lock order used by deletion requests: dataset row, then hold row.
                try(PreparedStatement p=c.prepareStatement("SELECT 1 FROM farabi_datasets WHERE id=? AND workspace_id=? FOR UPDATE")) {
                    p.setObject(1,h.datasetId());p.setString(2,workspace);
                    try(ResultSet r=p.executeQuery()){if(!r.next())throw new NoSuchElementException("dataset not found");}
                }
                try(PreparedStatement p=c.prepareStatement("UPDATE farabi_dataset_legal_holds SET released_by=?,released_at=?,release_reason=? WHERE id=? AND workspace_id=? AND released_at IS NULL")) {
                    p.setString(1,actor);p.setTimestamp(2,Timestamp.from(now));p.setString(3,reason);p.setObject(4,holdId);p.setString(5,workspace);
                    if(p.executeUpdate()!=1)throw new IllegalStateException("legal hold changed concurrently");
                }
                audit(c,holdId,workspace,h.datasetId(),"RELEASED",actor,reason,now);
                c.commit();
                return new Hold(h.id(),h.workspaceId(),h.datasetId(),h.reason(),h.placedBy(),h.placedAt(),actor,now,reason);
            } catch(Exception e) { c.rollback(); if(e instanceof RuntimeException re)throw re; throw new IllegalStateException("release legal hold failed",e); }
            finally { c.setAutoCommit(true); }
        } catch(SQLException e) { throw new IllegalStateException("release legal hold failed",e); }
    }

    public List<Hold> recent(String workspace, UUID datasetId, int limit) {
        if(limit<1||limit>100)throw new IllegalArgumentException("limit must be 1..100");
        String sql="SELECT * FROM farabi_dataset_legal_holds WHERE workspace_id=?"+(datasetId==null?"":" AND dataset_id=?")+" ORDER BY placed_at DESC LIMIT ?";
        try(Connection c=source.getConnection();PreparedStatement p=c.prepareStatement(sql)) {
            p.setString(1,workspace);int i=2;if(datasetId!=null)p.setObject(i++,datasetId);p.setInt(i,limit);
            try(ResultSet r=p.executeQuery()){List<Hold> out=new ArrayList<>();while(r.next())out.add(read(r));return List.copyOf(out);}
        } catch(SQLException e) { throw new IllegalStateException("read legal holds failed",e); }
    }

    private static void audit(Connection c,UUID id,String workspace,UUID dataset,String type,String actor,String reason,Instant now)throws SQLException {
        try(PreparedStatement p=c.prepareStatement("INSERT INTO farabi_dataset_legal_hold_audit(id,hold_id,workspace_id,dataset_id,event_type,actor_id,reason,occurred_at) VALUES(?,?,?,?,?,?,?,?)")) {
            p.setObject(1,UUID.randomUUID());p.setObject(2,id);p.setString(3,workspace);p.setObject(4,dataset);p.setString(5,type);p.setString(6,actor);p.setString(7,reason);p.setTimestamp(8,Timestamp.from(now));p.executeUpdate();
        }
    }
    private static Hold read(ResultSet r)throws SQLException {
        Timestamp released=r.getTimestamp("released_at");
        return new Hold(r.getObject("id",UUID.class),r.getString("workspace_id"),r.getObject("dataset_id",UUID.class),
            r.getString("reason"),r.getString("placed_by"),r.getTimestamp("placed_at").toInstant(),
            r.getString("released_by"),released==null?null:released.toInstant(),r.getString("release_reason"));
    }
    private static void required(String s,String n){if(s==null||s.isBlank())throw new IllegalArgumentException(n+" required");}
}
