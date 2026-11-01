package tech.kayys.farabi.engine;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
/** Read-only storage accounting reconciliation contracts. */
public final class DatasetStorageReconciliationPorts {
 private DatasetStorageReconciliationPorts() {}
 public enum FindingType { USED_BYTES_MISMATCH, RESERVED_BYTES_MISMATCH, COMMITTED_RESERVATION_MISMATCH }
 public record Finding(FindingType type,long ledgerBytes,long observedBytes,String detail) {
  public Finding { Objects.requireNonNull(type); if(ledgerBytes<0||observedBytes<0||detail==null||detail.isBlank()) throw new IllegalArgumentException("invalid finding"); }
 }
 public record Report(UUID id,String workspaceId,Instant inspectedAt,long quotaBytes,long ledgerUsedBytes,long observedUsedBytes,long ledgerReservedBytes,long observedReservedBytes,long committedReservationBytes,List<Finding> findings) {
  public Report { Objects.requireNonNull(id); if(workspaceId==null||workspaceId.isBlank())throw new IllegalArgumentException("workspaceId required"); Objects.requireNonNull(inspectedAt); if(quotaBytes<0||ledgerUsedBytes<0||observedUsedBytes<0||ledgerReservedBytes<0||observedReservedBytes<0||committedReservationBytes<0)throw new IllegalArgumentException("negative byte count"); findings=List.copyOf(Objects.requireNonNull(findings)); }
  public boolean consistent(){return findings.isEmpty();}
 }
 public interface Store { Report inspect(String workspaceId,Instant inspectedAt); List<Report> recent(String workspaceId,int limit); }
}
