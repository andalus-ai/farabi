package tech.kayys.farabi.engine;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.DashboardManifests.*;
import tech.kayys.farabi.engine.VisualizationContracts.*;
import static org.junit.jupiter.api.Assertions.*;
class DashboardTransportTest {
 @Test void preservesNullableCellsAndPublishedRevision(){
  var chart=new ChartSpec("revenue",Kind.BAR,"Revenue",new Binding("sales","region","amount",null),List.of());
  var spec=new DashboardSpec("Overview",List.of(chart),List.of(new Placement("revenue",0,0,12,3)));
  var manifest=new Manifest(UUID.randomUUID(),UUID.randomUUID(),"ws",3,1,spec,"agent","alice",Instant.EPOCH);
  Map<String,Object> row=new LinkedHashMap<>();row.put("region","North");row.put("amount",null);
  var data=new DashboardRuntime.ChartData("revenue","tenun.bar",List.of(row));
  row.put("region","MUTATED");
  var rendered=new DashboardRuntime.Rendered(RendererEnvelope.from(manifest),List.of(data));
  var doc=DashboardTransport.ready(rendered);
  assertEquals(3,doc.revision());
  assertEquals("ready",doc.status());
  assertNull(doc.charts().get(0).rows().get(0).get("amount"));
  assertEquals("North",doc.charts().get(0).rows().get(0).get("region"));
  assertThrows(UnsupportedOperationException.class,()->doc.charts().get(0).rows().get(0).put("region","other"));
 }
 @Test void supportsLoadingAndErrors(){
  var id=UUID.randomUUID();
  assertEquals("loading",DashboardTransport.loading(id).status());
  assertEquals("forbidden",DashboardTransport.error(id,"forbidden","Denied").error().code());
 }
}
