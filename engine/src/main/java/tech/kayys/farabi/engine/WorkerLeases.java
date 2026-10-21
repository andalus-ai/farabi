package tech.kayys.farabi.engine;
import java.time.*;
import java.util.*;
import tech.kayys.farabi.engine.QueryContracts.Status;
public final class WorkerLeases {
 private WorkerLeases(){}
 public record Lease(UUID executionId,String workspaceId,String workerId,long fence,Instant expiresAt){
  public Lease{Objects.requireNonNull(executionId);QueryContracts.required(workspaceId,"workspaceId");QueryContracts.required(workerId,"workerId");if(fence<1)throw new IllegalArgumentException("fence");Objects.requireNonNull(expiresAt);}
 }
 public interface Repository{
  Optional<Lease> claim(UUID executionId,String workspaceId,String workerId,Instant now,Duration ttl);
  boolean renew(Lease lease,Instant now,Duration ttl);
  boolean complete(Lease lease,Status expected,Status next,Instant now);
 }
}