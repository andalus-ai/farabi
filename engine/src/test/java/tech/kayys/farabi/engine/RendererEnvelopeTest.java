package tech.kayys.farabi.engine;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.VisualizationContracts.*;
import tech.kayys.farabi.engine.DashboardManifests.*;
import static org.junit.jupiter.api.Assertions.*;
class RendererEnvelopeTest {
 @Test void mapsAllChartKindsAndPreservesLayout(){
  var kinds=Kind.values();
  var charts=new ArrayList<ChartSpec>();
  var layout=new ArrayList<Placement>();
  for(int i=0;i<kinds.length;i++){
   String id="chart"+i;
   charts.add(new ChartSpec(id,kinds[i],id,new Binding("sales","month","amount",null),List.of(new Filter("region","eq","APAC"))));
   layout.add(new Placement(id,0,i,12,1));
  }
  var spec=new DashboardSpec("Sales",charts,layout);
  var m=new Manifest(UUID.randomUUID(),UUID.randomUUID(),"workspace",1,1,spec,"prompt","owner",Instant.EPOCH);
  var envelope=RendererEnvelope.from(m);
  assertEquals(kinds.length,envelope.charts().size());
  assertEquals("tenun.bar",envelope.charts().get(0).renderer());
  assertEquals("farabi.table",envelope.charts().get(4).renderer());
  assertEquals("APAC",envelope.charts().get(0).filters().get(0).value());
  assertEquals(layout.get(0),envelope.charts().get(0).placement());
  assertThrows(UnsupportedOperationException.class,()->envelope.charts().clear());
 }
}
