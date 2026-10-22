package tech.kayys.farabi.engine;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.DashboardManifests.*;
import tech.kayys.farabi.engine.VisualizationContracts.*;
import static org.junit.jupiter.api.Assertions.*;

class DashboardDeliveryTest {
 @Test void buildsReadyAndEmptyChartStatesAndPreservesNullCells() {
  var charts=List.of(
   new ChartSpec("a",Kind.BAR,"Revenue",new Binding("orders","region","total",null),List.of()),
   new ChartSpec("b",Kind.TABLE,"Detail",new Binding("orders",null,"total",null),List.of()));
  var spec=new DashboardSpec("Overview",charts,List.of(
   new Placement("a",0,0,6,3),new Placement("b",6,0,6,3)));
  var manifest=new Manifest(UUID.randomUUID(),UUID.randomUUID(),"ws",1,1,spec,null,"alice",Instant.EPOCH);
  var envelope=RendererEnvelope.from(manifest);
  var row=new LinkedHashMap<String,Object>();row.put("total",null);
  var rendered=new DashboardRuntime.Rendered(envelope,List.of(
   new DashboardRuntime.ChartData("a","tenun.bar",List.of(row)),
   new DashboardRuntime.ChartData("b","farabi.table",List.of())));
  var result=DashboardDelivery.from(rendered);
  assertEquals(DashboardDelivery.State.READY,result.charts().get(0).state());
  assertNull(result.charts().get(0).rows().get(0).get("total"));
  assertEquals(DashboardDelivery.State.EMPTY,result.charts().get(1).state());
  assertThrows(UnsupportedOperationException.class,()->result.charts().get(0).rows().get(0).put("total",5));
 }
}
