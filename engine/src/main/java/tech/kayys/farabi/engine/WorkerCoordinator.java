package tech.kayys.farabi.engine;
import java.time.*;
import java.util.*;
import tech.kayys.farabi.engine.QueryContracts.Status;
import tech.kayys.farabi.engine.WorkerLeases.*;
public final class WorkerCoordinator{
 private final Repository leases;private final Clock clock;
 public WorkerCoordinator(Repository leases,Clock clock){this.leases=Objects.requireNonNull(leases);this.clock=Objects.requireNonNull(clock);}
 public Optional<Lease> claim(UUID id,String workspace,String worker,Duration ttl){validate(ttl);QueryContracts.required(workspace,"workspace");QueryContracts.required(worker,"worker");return leases.claim(Objects.requireNonNull(id),workspace,worker,clock.instant(),ttl);}
 public boolean renew(Lease lease,Duration ttl){validate(ttl);return leases.renew(Objects.requireNonNull(lease),clock.instant(),ttl);}
 public boolean complete(Lease lease,Status expected,Status next){
  Objects.requireNonNull(expected);Objects.requireNonNull(next);
  if(!((expected==Status.SUBMITTED&&next==Status.RUNNING)||(expected==Status.RUNNING&&(next==Status.SUCCEEDED||next==Status.FAILED||next==Status.CANCELLED))))throw new IllegalArgumentException("illegal worker transition");
  return leases.complete(Objects.requireNonNull(lease),expected,next,clock.instant());
 }
 private static void validate(Duration ttl){if(ttl==null||ttl.isNegative()||ttl.isZero()||ttl.compareTo(Duration.ofHours(1))>0)throw new IllegalArgumentException("ttl must be in (0,1h]");}
}