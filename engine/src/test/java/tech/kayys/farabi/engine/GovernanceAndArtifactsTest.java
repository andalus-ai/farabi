package tech.kayys.farabi.engine;

import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.QueryContracts.*;
import tech.kayys.farabi.engine.DashboardArtifacts.*;
import static org.junit.jupiter.api.Assertions.*;

class GovernanceAndArtifactsTest {
    @Test void onlyGrantedDatasetsCompile() {
        var policy=new PolicyAuthorizer(List.of(new PolicyAuthorizer.Grant("ws","alice","toba","sales")));
        var compiler=new GovernedSemanticCompiler(List.of(new GovernedSemanticCompiler.Dataset(
            "sales","toba","analytics.orders",Map.of("revenue","amount"))));
        Request valid=new Request("ws","alice","toba","sales:revenue",Map.of());
        assertDoesNotThrow(()->policy.authorize(valid));
        assertEquals("SELECT SUM(amount) AS value FROM analytics.orders",compiler.compile(valid).sql());
        assertThrows(SecurityException.class,()->policy.authorize(new Request("ws","bob","toba","sales:revenue",Map.of())));
        assertThrows(SecurityException.class,()->policy.authorize(new Request("ws","alice","toba","secret:revenue",Map.of())));
        assertThrows(IllegalArgumentException.class,()->compiler.compile(new Request("ws","alice","toba","sales:revenue;DROP TABLE x",Map.of())));
    }
    @Test void rejectsUnsafeCatalogIdentifiers() {
        assertThrows(IllegalArgumentException.class,()->new GovernedSemanticCompiler.Dataset(
            "sales","toba","orders;drop",Map.of("revenue","amount")));
    }
    @Test void publishesImmutableSnapshotWithWorkspaceIsolation() {
        Map<UUID,Published> data=new HashMap<>();
        Repository repo=new Repository() {
            public void save(Published a){data.put(a.id(),a);}
            public Optional<Published> find(UUID id,String workspaceId){return Optional.ofNullable(data.get(id)).filter(a->a.workspaceId().equals(workspaceId));}
        };
        var service=new DashboardPromotionService(repo,Clock.systemUTC());
        var mutable=new ArrayList<>(List.of(new Chart("chart","bar","sales","revenue")));
        var draft=new Draft(UUID.randomUUID(),"ws","alice","Sales",mutable,"prompt");
        mutable.clear();
        Published published=service.publish(draft,"ws","alice",1);
        assertEquals(1,published.charts().size());
        assertEquals(published,service.get(published.id(),"ws"));
        assertThrows(SecurityException.class,()->service.publish(draft,"other","alice",2));
        assertThrows(IllegalArgumentException.class,()->service.get(published.id(),"other"));
    }
}
