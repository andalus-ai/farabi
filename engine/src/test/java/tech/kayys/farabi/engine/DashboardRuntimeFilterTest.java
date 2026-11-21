package tech.kayys.farabi.engine;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.DashboardManifests.*;
import tech.kayys.farabi.engine.VisualizationContracts.*;
import static org.junit.jupiter.api.Assertions.*;

class DashboardRuntimeFilterTest {
 @Test void passesPredicatesToTrustedAdapterAndCachesIdenticalDatasetFilterPairs() {
  var filter=new Filter("region","eq","west");
  var chart=new ChartSpec("west",Kind.BAR,"West",new Binding("sales","region","amount",null),List.of(filter));
  var chart2=new ChartSpec("west-copy",Kind.LINE,"West copy",new Binding("sales","region","amount",null),List.of(filter));
  var spec=new DashboardSpec("Sales",List.of(chart,chart2),List.of(
      new Placement("west",0,0,6,2),new Placement("west-copy",6,0,6,2)));
  var manifest=new Manifest(UUID.randomUUID(),UUID.randomUUID(),"ws",1,1,spec,null,"alice",Instant.EPOCH);
  var calls=new ArrayList<List<Filter>>();
  DashboardRuntime.DatasetAccess access=new DashboardRuntime.DatasetAccess(){
   public void authorize(String w,String s,String d){}
   public List<Map<String,Object>> fetch(String w,String s,String d,int limit){
    fail("filtered fetch must not fall back to unfiltered execution");return List.of();
   }
   public List<Map<String,Object>> fetch(String w,String s,String d,List<Filter> filters,int limit){
    calls.add(filters);return List.of(Map.of("region","west","amount",10));
   }
  };
  var runtime=new DashboardRuntime(repository(manifest),access);
  var rendered=runtime.load(manifest.id(),"ws","alice",100);
  assertEquals(List.of(List.of(filter)),calls);
  assertEquals(2,rendered.data().size());
 }
 @Test void failsClosedWhenAdapterCannotEnforceFilters() {
  var filter=new Filter("region","eq","west");
  var chart=new ChartSpec("west",Kind.BAR,"West",new Binding("sales","region","amount",null),List.of(filter));
  var spec=new DashboardSpec("Sales",List.of(chart),List.of(new Placement("west",0,0,12,2)));
  var manifest=new Manifest(UUID.randomUUID(),UUID.randomUUID(),"ws",1,1,spec,null,"alice",Instant.EPOCH);
  DashboardRuntime.DatasetAccess access=new DashboardRuntime.DatasetAccess(){
   public void authorize(String w,String s,String d){}
   public List<Map<String,Object>> fetch(String w,String s,String d,int limit){return List.of(Map.of("region","east","amount",999));}
  };
  var runtime=new DashboardRuntime(repository(manifest),access);
  assertThrows(UnsupportedOperationException.class,()->runtime.load(manifest.id(),"ws","alice",100));
 }
 private static Repository repository(Manifest manifest) {
  return new Repository(){
   public void save(Manifest ignored){}
   public Optional<Manifest> find(UUID id,String workspace){
    return id.equals(manifest.id())&&workspace.equals(manifest.workspaceId())?Optional.of(manifest):Optional.empty();
   }
  };
 }
}
