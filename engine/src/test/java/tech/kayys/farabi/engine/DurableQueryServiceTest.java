package tech.kayys.farabi.engine;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.QueryContracts.*;
import tech.kayys.farabi.engine.QueryPorts.*;
import static org.junit.jupiter.api.Assertions.*;

class DurableQueryServiceTest {
    private static final Request REQUEST=new Request("ws","alice","toba","sales",Map.of());
    static class Store implements ExecutionRepository {
        Map<UUID,Execution> items=new HashMap<>();
        public void create(Execution e){items.put(e.id(),e);}
        public Optional<Execution> find(UUID id,String workspaceId){return Optional.ofNullable(items.get(id)).filter(e->e.workspaceId().equals(workspaceId));}
        public boolean transition(UUID id,String workspaceId,Status expected,Status next,String providerId){
            Execution old=items.get(id);
            if(old==null||!old.workspaceId().equals(workspaceId)||old.status()!=expected)return false;
            items.put(id,new Execution(id,old.workspaceId(),old.subjectId(),old.sourceId(),providerId,next,old.createdAt(),old.updatedAt().plusSeconds(1)));
            return true;
        }
    }
    @Test void persistsBeforeRemoteAndScopesRead() {
        Store store=new Store();
        var svc=new DurableQueryService(r->{},r->new Plan("toba","select 1"),
            p->{assertEquals(Status.PENDING,store.items.values().iterator().next().status());return new Submission("remote-1");},
            store,Clock.systemUTC());
        Execution result=svc.submit(REQUEST);
        assertEquals(Status.SUBMITTED,result.status());assertEquals("remote-1",result.providerJobId());
        assertEquals(result,svc.get(result.id(),"ws","alice"));
        assertThrows(IllegalArgumentException.class,()->svc.get(result.id(),"other","alice"));
        assertThrows(IllegalArgumentException.class,()->svc.get(result.id(),"ws","bob"));
    }
    @Test void denyBeforeCompileAndPersist() {
        Store store=new Store();AtomicInteger count=new AtomicInteger();
        var svc=new DurableQueryService(r->{throw new SecurityException("denied");},
            r->{count.incrementAndGet();return new Plan("toba","select 1");},
            p->{count.incrementAndGet();return new Submission("id");},store,Clock.systemUTC());
        assertThrows(SecurityException.class,()->svc.submit(REQUEST));
        assertEquals(0,count.get());assertTrue(store.items.isEmpty());
    }
    @Test void sourceMismatchNeverExecutes() {
        Store store=new Store();
        var svc=new DurableQueryService(r->{},r->new Plan("different","select 1"),
            p->{fail("must not execute");return new Submission("id");},store,Clock.systemUTC());
        assertThrows(IllegalStateException.class,()->svc.submit(REQUEST));assertTrue(store.items.isEmpty());
    }
    @Test void ambiguousRemoteFailureStaysPending() {
        Store store=new Store();
        var svc=new DurableQueryService(r->{},r->new Plan("toba","select 1"),
            p->{throw new IllegalStateException("timeout");},store,Clock.systemUTC());
        assertThrows(IllegalStateException.class,()->svc.submit(REQUEST));
        assertEquals(Status.PENDING,store.items.values().iterator().next().status());
    }
}
