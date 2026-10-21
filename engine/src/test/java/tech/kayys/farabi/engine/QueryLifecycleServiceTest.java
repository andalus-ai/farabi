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
}
