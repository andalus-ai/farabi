package tech.kayys.farabi.jdbc;

import java.sql.*;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import tech.kayys.farabi.engine.DatasetStorageAccountingPorts.*;

/** Transactional workspace quota reservations and usage snapshots. */
public final class JdbcWorkspaceStorageStore implements QuotaStore {
    private final DataSource source;
    private final long defaultQuotaBytes;

    public JdbcWorkspaceStorageStore(DataSource source,long defaultQuotaBytes) {
        this.source=Objects.requireNonNull(source);
        if(defaultQuotaBytes<0)throw new IllegalArgumentException("default quota cannot be negative");
        this.defaultQuotaBytes=defaultQuotaBytes;
    }

    @Override public Reservation reserve(String workspace,UUID id,long bytes,Instant now) {
        if(workspace==null||workspace.isBlank()||id==null||bytes<=0)throw new IllegalArgumentException("invalid reservation");
        try(Connection c=source.getConnection()){
            c.setAutoCommit(false);
            try{
                try(PreparedStatement p=c.prepareStatement("INSERT INTO farabi_workspace_storage(workspace_id,quota_bytes,used_bytes,reserved_bytes,updated_at) VALUES(?,?,0,0,?) ON CONFLICT(workspace_id) DO NOTHING")){
                    p.setString(1,workspace);p.setLong(2,defaultQuotaBytes);p.setTimestamp(3,Timestamp.from(now));p.executeUpdate();
                }
                int updated;
                try(PreparedStatement p=c.prepareStatement("UPDATE farabi_workspace_storage SET reserved_bytes=reserved_bytes+?,updated_at=? WHERE workspace_id=? AND used_bytes+reserved_bytes+?<=quota_bytes")){
                    p.setLong(1,bytes);p.setTimestamp(2,Timestamp.from(now));p.setString(3,workspace);p.setLong(4,bytes);updated=p.executeUpdate();
                }
                if(updated!=1)throw new StorageQuotaExceededException("workspace storage quota exceeded");
                try(PreparedStatement p=c.prepareStatement("INSERT INTO farabi_storage_reservations(id,workspace_id,reserved_bytes,status,created_at,updated_at) VALUES(?,?,?,'RESERVED',?,?)")){
                    p.setObject(1,id);p.setString(2,workspace);p.setLong(3,bytes);p.setTimestamp(4,Timestamp.from(now));p.setTimestamp(5,Timestamp.from(now));p.executeUpdate();
                }
                c.commit();return new Reservation(id,workspace,bytes,now);
            }catch(Exception e){c.rollback();if(e instanceof RuntimeException re)throw re;throw new IllegalStateException("reserve workspace storage failed",e);}
            finally{c.setAutoCommit(true);}
        }catch(SQLException e){throw new IllegalStateException("reserve workspace storage failed",e);}
    }

    @Override public Optional<Usage> usage(String workspace) {
        try(Connection c=source.getConnection();PreparedStatement p=c.prepareStatement("SELECT quota_bytes,used_bytes,reserved_bytes FROM farabi_workspace_storage WHERE workspace_id=?")){
            p.setString(1,workspace);try(ResultSet r=p.executeQuery()){return r.next()?Optional.of(new Usage(workspace,r.getLong(1),r.getLong(2),r.getLong(3))):Optional.empty();}
        }catch(SQLException e){throw new IllegalStateException("read workspace storage usage failed",e);}
    }

    @Override public void releaseReservation(UUID id,String workspace,Instant now) {
        try(Connection c=source.getConnection()){
            c.setAutoCommit(false);
            try{
                long bytes;
                try(PreparedStatement p=c.prepareStatement("SELECT reserved_bytes FROM farabi_storage_reservations WHERE id=? AND workspace_id=? AND status='RESERVED' FOR UPDATE")){
                    p.setObject(1,id);p.setString(2,workspace);try(ResultSet r=p.executeQuery()){if(!r.next()){c.commit();return;}bytes=r.getLong(1);}
                }
                try(PreparedStatement p=c.prepareStatement("UPDATE farabi_workspace_storage SET reserved_bytes=reserved_bytes-?,updated_at=? WHERE workspace_id=? AND reserved_bytes>=?")){
                    p.setLong(1,bytes);p.setTimestamp(2,Timestamp.from(now));p.setString(3,workspace);p.setLong(4,bytes);
                    if(p.executeUpdate()!=1)throw new IllegalStateException("workspace reservation ledger is inconsistent");
                }
                try(PreparedStatement p=c.prepareStatement("UPDATE farabi_storage_reservations SET status='RELEASED',updated_at=? WHERE id=? AND status='RESERVED'")){
                    p.setTimestamp(1,Timestamp.from(now));p.setObject(2,id);p.executeUpdate();
                }
                c.commit();
            }catch(Exception e){c.rollback();if(e instanceof RuntimeException re)throw re;throw new IllegalStateException(e);}
            finally{c.setAutoCommit(true);}
        }catch(SQLException e){throw new IllegalStateException("release workspace storage reservation failed",e);}
    }

    public static final class StorageQuotaExceededException extends RuntimeException {
        public StorageQuotaExceededException(String message){super(message);}
    }
}
