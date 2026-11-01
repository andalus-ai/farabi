package tech.kayys.farabi.engine;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.QueryContracts.*;
import tech.kayys.farabi.engine.QueryLifecycle.*;
import static org.junit.jupiter.api.Assertions.*;

class QueryLifecycleServiceTest {
    @Test void scopesProviderAccessAndValidatesPaging() {
        UUID id=UUID.randomUUID();
        Execution execution=new Execution(id,"workspace","alice","toba","job-1",Status.SUCCEEDED,Instant.now(),Instant.now());
        QueryPorts.ExecutionRepository repository=new QueryPorts.ExecutionRepository() {
            public void create(Execution e) {}
            public Optional<Execution> find(UUID key,String workspace) {
                return key.equals(id)&&workspace.equals("workspace")?Optional.of(execution):Optional.empty();
            }
            public boolean transition(UUID key,String ws,Status expected,Status next,String remote){return false;}
        };
        List<String> calls=new ArrayList<>();
        Provider provider=new Provider() {
            public RemoteState status(String job){calls.add("status");return new RemoteState(Status.SUCCEEDED,null);}
            public ResultPage results(String job,int offset,int limit){calls.add("results");return new ResultPage(List.of("value"),List.of(Map.of("value",42)),offset,false);}
            public void cancel(String job){calls.add("cancel");}
        };
        var service=new QueryLifecycleService(repository,provider);
        assertThrows(IllegalArgumentException.class,()->service.results(id,"other","alice",0,10));
        assertThrows(IllegalArgumentException.class,()->service.status(id,"workspace","bob"));
        assertThrows(IllegalArgumentException.class,()->service.results(id,"workspace","alice",0,1001));
        assertTrue(calls.isEmpty());
        assertEquals(42,service.results(id,"workspace","alice",0,10).rows().get(0).get("value"));
        assertEquals(List.of("results"),calls);
    }
    @Test void cancellationOnlyReachesProviderForActiveJobsWithKnownRemoteIds() {
        UUID id=UUID.randomUUID();
        List<String> calls=new ArrayList<>();
        Provider provider=new Provider() {
            public RemoteState status(String job){calls.add("status");return new RemoteState(Status.RUNNING,null);}
            public ResultPage results(String job,int offset,int limit){calls.add("results");return new ResultPage(List.of(),List.of(),offset,false);}
            public void cancel(String job){calls.add("cancel:"+job);}
        };
        Execution running=new Execution(id,"workspace","alice","toba","job-running",Status.RUNNING,Instant.EPOCH,Instant.EPOCH);
        new QueryLifecycleService(repository(running),provider).cancel(id,"workspace","alice");
        assertEquals(List.of("cancel:job-running"),calls);
        for (Status terminal : List.of(Status.SUCCEEDED,Status.FAILED,Status.CANCELLED)) {
            Execution done=new Execution(id,"workspace","alice","toba","job-terminal",terminal,Instant.EPOCH,Instant.EPOCH);
            assertThrows(IllegalStateException.class,() -> new QueryLifecycleService(repository(done),provider).cancel(id,"workspace","alice"));
        }
        Execution pending=new Execution(id,"workspace","alice","toba",null,Status.PENDING,Instant.EPOCH,Instant.EPOCH);
        assertThrows(IllegalStateException.class,() -> new QueryLifecycleService(repository(pending),provider).cancel(id,"workspace","alice"));
        assertEquals(List.of("cancel:job-running"),calls,
            "terminal and ambiguous pending executions must not reach the remote provider");
    }

    @Test void providerCancellationFailurePropagatesWithoutPretendingItSucceeded() {
        UUID id=UUID.randomUUID();
        Execution running=new Execution(id,"workspace","alice","toba","job-1",Status.RUNNING,Instant.EPOCH,Instant.EPOCH);
        Provider provider=new Provider() {
            public RemoteState status(String job){return new RemoteState(Status.RUNNING,null);}
            public ResultPage results(String job,int offset,int limit){return new ResultPage(List.of(),List.of(),offset,false);}
            public void cancel(String job){throw new IllegalStateException("provider unavailable");}
        };
        var service=new QueryLifecycleService(repository(running),provider);
        IllegalStateException error=assertThrows(IllegalStateException.class,() -> service.cancel(id,"workspace","alice"));
        assertEquals("provider unavailable",error.getMessage());
    }


    @Test void statusAndResultsNeverReachProviderForIneligibleLocalExecutions() {
        UUID id=UUID.randomUUID();
        List<String> calls=new ArrayList<>();
        Provider provider=new Provider() {
            public RemoteState status(String job){calls.add("status:"+job);return new RemoteState(Status.RUNNING,null);}
            public ResultPage results(String job,int offset,int limit){calls.add("results:"+job);return new ResultPage(List.of(),List.of(),offset,false);}
            public void cancel(String job){calls.add("cancel:"+job);}
        };
        Execution pending=new Execution(id,"workspace","alice","toba",null,Status.PENDING,Instant.EPOCH,Instant.EPOCH);
        var pendingService=new QueryLifecycleService(repository(pending),provider);
        assertThrows(IllegalStateException.class,()->pendingService.status(id,"workspace","alice"));
        assertThrows(IllegalStateException.class,()->pendingService.results(id,"workspace","alice",0,10));

        for (Status status : List.of(Status.PENDING,Status.SUBMITTED,Status.RUNNING,Status.FAILED,Status.CANCELLED)) {
            Execution notSucceeded=new Execution(id,"workspace","alice","toba","job-"+status,status,Instant.EPOCH,Instant.EPOCH);
            assertThrows(IllegalStateException.class,
                ()->new QueryLifecycleService(repository(notSucceeded),provider).results(id,"workspace","alice",0,10));
        }
        assertTrue(calls.isEmpty(),"ineligible executions must not trigger provider calls");
    }

    @Test void providerStatusAndResultFailuresPropagateToCaller() {
        UUID id=UUID.randomUUID();
        Execution succeeded=new Execution(id,"workspace","alice","toba","job-1",Status.SUCCEEDED,Instant.EPOCH,Instant.EPOCH);
        Provider provider=new Provider() {
            public RemoteState status(String job){throw new IllegalStateException("status unavailable");}
            public ResultPage results(String job,int offset,int limit){throw new IllegalStateException("results unavailable");}
            public void cancel(String job){}
        };
        var service=new QueryLifecycleService(repository(succeeded),provider);
        assertEquals("status unavailable",
            assertThrows(IllegalStateException.class,()->service.status(id,"workspace","alice")).getMessage());
        assertEquals("results unavailable",
            assertThrows(IllegalStateException.class,()->service.results(id,"workspace","alice",0,10)).getMessage());
    }

    private static QueryPorts.ExecutionRepository repository(Execution execution) {
        return new QueryPorts.ExecutionRepository() {
            public void create(Execution ignored) {}
            public Optional<Execution> find(UUID key,String workspace) {
                return key.equals(execution.id()) && workspace.equals(execution.workspaceId()) ? Optional.of(execution) : Optional.empty();
            }
            public boolean transition(UUID key,String workspace,Status expected,Status next,String remote) { return false; }
        };
    }

}
