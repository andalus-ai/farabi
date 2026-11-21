package tech.kayys.farabi.jdbc;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import tech.kayys.farabi.engine.DatasetStorageReconciliationPorts.*;
/** Read-only reconciliation of quota counters against dataset and reservation metadata. */
public final class JdbcDatasetStorageReconciliationStore implements Store {
 private final DataSource source;
 public JdbcDatasetStorageReconciliationStore(DataSource source){this.source=Objects.requireNonNull(source);}
 @Override public Report inspect(String workspace,Instant at){
  required(workspace);Objects.requireNonNull(at);
  String sql="""
   SELECT ws.quota_bytes,ws.used_bytes,ws.reserved_bytes,
    COALESCE((SELECT SUM(d.storage_bytes) FROM farabi_datasets d WHERE d.workspace_id=ws.workspace_id AND d.lifecycle<>'ARCHIVED'),0) dataset_bytes,
    COALESCE((SELECT SUM(r.reserved_bytes) FROM farabi_storage_reservations r WHERE r.workspace_id=ws.workspace_id AND r.status='RESERVED'),0) reservation_bytes,
    COALESCE((SELECT SUM(r.reserved_bytes) FROM farabi_storage_reservations r JOIN farabi_datasets d ON d.id=r.dataset_id AND d.workspace_id=r.workspace_id WHERE r.workspace_id=ws.workspace_id AND r.status='COMMITTED'),0) committed_bytes
   FROM farabi_workspace_storage ws WHERE ws.workspace_id=?
   """;
  try(Connection c=source.getConnection();PreparedStatement p=c.prepareStatement(sql)){
   p.setString(1,workspace);try(ResultSet r=p.executeQuery()){
    if(!r.next())throw new NoSuchElementException("workspace storage ledger not found");
    long quota=r.getLong("quota_bytes"),used=r.getLong("used_bytes"),reserved=r.getLong("reserved_bytes");
    long datasets=r.getLong("dataset_bytes"),reservations=r.getLong("reservation_bytes"),committed=r.getLong("committed_bytes");
    if(datasets<0||reservations<0||committed<0)throw new IllegalStateException("storage aggregate overflow");
    List<Finding> f=new ArrayList<>();
    if(used!=datasets)f.add(new Finding(FindingType.USED_BYTES_MISMATCH,used,datasets,"ledger used bytes differ from non-archived dataset metadata"));
    if(reserved!=reservations)f.add(new Finding(FindingType.RESERVED_BYTES_MISMATCH,reserved,reservations,"ledger reserved bytes differ from active reservation rows"));
    if(committed!=datasets)f.add(new Finding(FindingType.COMMITTED_RESERVATION_MISMATCH,committed,datasets,"committed reservation bytes differ from non-archived dataset metadata"));
    Report report=new Report(UUID.randomUUID(),workspace,at,quota,used,datasets,reserved,reservations,committed,f);persist(c,report);return report;
   }
  }catch(SQLException e){throw new IllegalStateException("inspect storage reconciliation failed",e);}
 }
 @Override public List<Report> recent(String workspace,int limit){
  required(workspace);if(limit<1||limit>100)throw new IllegalArgumentException("limit must be between 1 and 100");
  String sql="SELECT * FROM farabi_storage_reconciliation_reports WHERE workspace_id=? ORDER BY inspected_at DESC,id DESC LIMIT ?";
  try(Connection c=source.getConnection();PreparedStatement p=c.prepareStatement(sql)){
   p.setString(1,workspace);p.setInt(2,limit);try(ResultSet r=p.executeQuery()){
    List<Report> out=new ArrayList<>();while(r.next()){
     List<Finding> f=new ArrayList<>();String types=r.getString("finding_types");
     if(types!=null&&!types.isBlank())for(String name:types.split(",")){FindingType t=FindingType.valueOf(name);long ledger=t==FindingType.USED_BYTES_MISMATCH?r.getLong("ledger_used_bytes"):t==FindingType.RESERVED_BYTES_MISMATCH?r.getLong("ledger_reserved_bytes"):r.getLong("committed_reservation_bytes");long observed=t==FindingType.RESERVED_BYTES_MISMATCH?r.getLong("observed_reserved_bytes"):r.getLong("observed_used_bytes");f.add(new Finding(t,ledger,observed,t.name()));}
     out.add(new Report(r.getObject("id",UUID.class),workspace,r.getTimestamp("inspected_at").toInstant(),r.getLong("quota_bytes"),r.getLong("ledger_used_bytes"),r.getLong("observed_used_bytes"),r.getLong("ledger_reserved_bytes"),r.getLong("observed_reserved_bytes"),r.getLong("committed_reservation_bytes"),f));
    }return List.copyOf(out);
   }
  }catch(SQLException e){throw new IllegalStateException("read storage reconciliation reports failed",e);}
 }
 private static void persist(Connection c,Report r)throws SQLException{
  String types=r.findings().stream().map(x->x.type().name()).distinct().sorted().reduce((a,b)->a+","+b).orElse("");
  String sql="INSERT INTO farabi_storage_reconciliation_reports(id,workspace_id,inspected_at,quota_bytes,ledger_used_bytes,observed_used_bytes,ledger_reserved_bytes,observed_reserved_bytes,committed_reservation_bytes,finding_types) VALUES(?,?,?,?,?,?,?,?,?,?)";
  try(PreparedStatement p=c.prepareStatement(sql)){p.setObject(1,r.id());p.setString(2,r.workspaceId());p.setTimestamp(3,Timestamp.from(r.inspectedAt()));p.setLong(4,r.quotaBytes());p.setLong(5,r.ledgerUsedBytes());p.setLong(6,r.observedUsedBytes());p.setLong(7,r.ledgerReservedBytes());p.setLong(8,r.observedReservedBytes());p.setLong(9,r.committedReservationBytes());p.setString(10,types);p.executeUpdate();}
 }
 private static void required(String s){if(s==null||s.isBlank())throw new IllegalArgumentException("workspace required");}
}
