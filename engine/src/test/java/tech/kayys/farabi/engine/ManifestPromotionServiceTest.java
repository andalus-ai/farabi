package tech.kayys.farabi.engine;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.DashboardManifests.*;
import tech.kayys.farabi.engine.VisualizationContracts.*;
import static org.junit.jupiter.api.Assertions.*;

class ManifestPromotionServiceTest {
 @Test void preservesCompleteLayoutAndFilters() {
  var chart=new ChartSpec("sales",Kind.BAR,"Sales",
      new Binding("orders","region","revenue",null),List.of(new Filter("region","eq","APAC")));
  var spec=new DashboardSpec("Revenue",List.of(chart),List.of(new Placement("sales",0,0,12,4)));
  var composition=new VisualizationComposer().compose("ws","alice",spec,"agent:request-1");
  Map<UUID,Manifest> data=new HashMap<>();
  Repository repo=new Repository(){
   public void save(Manifest m){data.put(m.id(),m);}
   public Optional<Manifest> find(UUID id,String ws){return Optional.ofNullable(data.get(id)).filter(m->m.workspaceId().equals(ws));}
  };
  var service=new ManifestPromotionService(repo,Clock.fixed(Instant.parse("2026-10-21T00:00:00Z"),ZoneOffset.UTC));
  var published=service.publish(composition,"ws","alice",1);
  assertEquals(spec,service.get(published.id(),"ws").spec());
  assertEquals("APAC",published.spec().charts().get(0).filters().get(0).value());
  assertEquals("agent:request-1",published.promptProvenance());
  assertThrows(SecurityException.class,()->service.publish(composition,"other","alice",2));
  assertThrows(IllegalArgumentException.class,()->service.get(published.id(),"other"));
 }
}
