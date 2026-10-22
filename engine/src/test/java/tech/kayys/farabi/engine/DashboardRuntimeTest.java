package tech.kayys.farabi.engine;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.DashboardManifests.*;
import tech.kayys.farabi.engine.VisualizationContracts.*;
import static org.junit.jupiter.api.Assertions.*;
class DashboardRuntimeTest {
 @Test void authorizesAllDatasetsBeforeFetching(){
  var spec=new DashboardSpec("Overview",List.of(
   new ChartSpec("one",Kind.BAR,"One",new Binding("allowed","x","y",null),List.of()),
   new ChartSpec("two",Kind.LINE,"Two",new Binding("denied","x","y",null),List.of())),
   List.of(new Placement("one",0,0,6,2),new Placement("two",6,0,6,2)));
  var m=new Manifest(UUID.randomUUID(),UUID.randomUUID(),"ws",1,1,spec,null,"alice",Instant.EPOCH);
  Repository repository=new Repository(){
   public void save(Manifest ignored){}
   public Optional<Manifest> find(UUID id,String workspace){return id.equals(m.id())&&workspace.equals("ws")?Optional.of(m):Optional.empty();}
  };
  List<String> calls=new ArrayList<>();
  DashboardRuntime.DatasetAccess access=new DashboardRuntime.DatasetAccess(){
   public void authorize(String ws,String subject,String dataset){
    calls.add("auth:"+dataset);
    if(dataset.equals("denied"))throw new SecurityException("denied");
   }
   public List<Map<String,Object>> fetch(String ws,String subject,String dataset,int limit){
    calls.add("fetch:"+dataset);return List.of(Map.of("x","A","y",3));
   }
  };
  var runtime=new DashboardRuntime(repository,access);
  assertThrows(SecurityException.class,()->runtime.load(m.id(),"ws","alice",100));
  assertEquals(List.of("auth:allowed","auth:denied"),calls);
  assertThrows(IllegalArgumentException.class,()->runtime.load(m.id(),"other","alice",100));
 }
}
