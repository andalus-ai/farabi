package tech.kayys.farabi.engine;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.QueryContracts.Status;
import tech.kayys.farabi.engine.WorkerLeases.*;
import static org.junit.jupiter.api.Assertions.*;
class WorkerCoordinatorTest{
 @Test void rejectsInvalidTtlAndTransitions(){
  Repository repo=new Repository(){
   public Optional<Lease> claim(UUID id,String ws,String worker,Instant now,Duration ttl){return Optional.empty();}
   public boolean renew(Lease l,Instant now,Duration ttl){return false;}
   public boolean complete(Lease l,Status expected,Status next,Instant now){return false;}
  };
  var c=new WorkerCoordinator(repo,Clock.systemUTC());
  assertThrows(IllegalArgumentException.class,()->c.claim(UUID.randomUUID(),"ws","worker",Duration.ZERO));
  assertThrows(IllegalArgumentException.class,()->c.claim(UUID.randomUUID(),"ws","worker",Duration.ofHours(2)));
  var lease=new Lease(UUID.randomUUID(),"ws","worker",1,Instant.now().plusSeconds(30));
  assertThrows(IllegalArgumentException.class,()->c.complete(lease,Status.PENDING,Status.SUCCEEDED));
 }
}