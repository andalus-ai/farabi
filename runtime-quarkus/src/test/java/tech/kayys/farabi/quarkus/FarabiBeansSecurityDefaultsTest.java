package tech.kayys.farabi.quarkus;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.DurableQueryService;
import tech.kayys.farabi.engine.QueryContracts.*;
import tech.kayys.farabi.engine.QueryPorts.*;
import static org.junit.jupiter.api.Assertions.*;

class FarabiBeansSecurityDefaultsTest {
 private final FarabiBeans beans=new FarabiBeans();
 private final Request request=new Request("workspace-a","alice","source-a","sales:revenue",Map.of());

 @Test void defaultAuthorizerDeniesBeforeCompilerRepositoryOrExecutor() {
  AtomicInteger compileCalls=new AtomicInteger();
  AtomicInteger createCalls=new AtomicInteger();
  AtomicInteger executeCalls=new AtomicInteger();
  Authorizer defaultAuthorizer=beans.authorizer();
  Compiler compiler=req->{compileCalls.incrementAndGet();return new Plan(req.sourceId(),"select 1");};
  var service=new DurableQueryService(defaultAuthorizer,compiler,
      plan->{executeCalls.incrementAndGet();return new Submission("remote-1");},
      repository(createCalls),Clock.systemUTC());

  assertThrows(SecurityException.class,()->service.submit(request));
  assertEquals(0,compileCalls.get());
  assertEquals(0,createCalls.get());
  assertEquals(0,executeCalls.get());
 }

 @Test void defaultCompilerPreventsPersistenceAndRemoteExecution() {
  AtomicInteger createCalls=new AtomicInteger();
  AtomicInteger executeCalls=new AtomicInteger();
  var service=new DurableQueryService(req->{},beans.compiler(),
      plan->{executeCalls.incrementAndGet();return new Submission("remote-1");},
      repository(createCalls),Clock.systemUTC());

  assertThrows(IllegalStateException.class,()->service.submit(request));
  assertEquals(0,createCalls.get());
  assertEquals(0,executeCalls.get());
 }

 private static ExecutionRepository repository(AtomicInteger createCalls) {
  return new ExecutionRepository(){
   public void create(Execution execution){createCalls.incrementAndGet();}
   public Optional<Execution> find(UUID id,String workspace){return Optional.empty();}
   public boolean transition(UUID id,String workspace,Status expected,Status next,String providerJobId){return false;}
  };
 }
}
