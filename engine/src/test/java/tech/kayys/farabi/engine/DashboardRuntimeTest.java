package tech.kayys.farabi.engine;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.DashboardManifests.*;
import tech.kayys.farabi.engine.VisualizationContracts.*;
import static org.junit.jupiter.api.Assertions.*;

class DashboardRuntimeTest {
 @Test void authorizesEveryDatasetBeforeFetchingAnyDataset() {
  var manifest=manifest("ws",List.of(chart("one","allowed"),chart("two","denied")));
  var calls=new ArrayList<String>();
  var runtime=runtime(manifest,calls,(ws,subject,dataset)->{
   calls.add("auth:"+dataset);
   if(dataset.equals("denied")) throw new SecurityException("denied");
  });
  assertThrows(SecurityException.class,()->runtime.load(manifest.id(),"ws","alice",100));
  assertEquals(List.of("auth:allowed","auth:denied"),calls,
      "a denial must prevent every fetch, even for previously authorized datasets");
 }

 @Test void scopesManifestLookupToCallerWorkspaceBeforeDatasetAccess() {
  var manifest=manifest("workspace-a",List.of(chart("one","sales")));
  var calls=new ArrayList<String>();
  var runtime=runtime(manifest,calls,(ws,subject,dataset)->calls.add("auth:"+dataset));
  assertThrows(IllegalArgumentException.class,
      ()->runtime.load(manifest.id(),"workspace-b","alice",100));
  assertTrue(calls.isEmpty(),"a manifest from another workspace must not reach dataset authorization or fetch");
 }

 @Test void rejectsOutOfRangeLimitsBeforeDatasetAccess() {
  var manifest=manifest("ws",List.of(chart("one","sales")));
  var calls=new ArrayList<String>();
  var runtime=runtime(manifest,calls,(ws,subject,dataset)->calls.add("auth:"+dataset));
  assertThrows(IllegalArgumentException.class,()->runtime.load(manifest.id(),"ws","alice",0));
  assertThrows(IllegalArgumentException.class,()->runtime.load(manifest.id(),"ws","alice",1001));
  assertTrue(calls.isEmpty(),"invalid limits must not invoke dataset adapters");
 }

 @Test void authorizesAndFetchesEachDistinctDatasetOnlyOnce() {
  var manifest=manifest("ws",List.of(chart("one","sales"),chart("two","sales")));
  var calls=new ArrayList<String>();
  DashboardRuntime.DatasetAccess access=new DashboardRuntime.DatasetAccess(){
   public void authorize(String ws,String subject,String dataset){calls.add("auth:"+dataset);}
   public List<Map<String,Object>> fetch(String ws,String subject,String dataset,int limit){
    calls.add("fetch:"+dataset); return List.of(Map.of("region","west","total",7));
   }
  };
  var rendered=new DashboardRuntime(repository(manifest),access).load(manifest.id(),"ws","alice",100);
  assertEquals(List.of("auth:sales","fetch:sales"),calls);
  assertEquals(2,rendered.data().size());
  assertEquals(rendered.data().get(0).rows(),rendered.data().get(1).rows());
 }

 private static DashboardRuntime runtime(Manifest manifest,List<String> calls,Authorizer authorizer) {
  DashboardRuntime.DatasetAccess access=new DashboardRuntime.DatasetAccess(){
   public void authorize(String ws,String subject,String dataset){authorizer.authorize(ws,subject,dataset);}
   public List<Map<String,Object>> fetch(String ws,String subject,String dataset,int limit){
    calls.add("fetch:"+dataset);return List.of(Map.of("region","west","total",7));
   }
  };
  return new DashboardRuntime(repository(manifest),access);
 }
 @FunctionalInterface private interface Authorizer { void authorize(String workspace,String subject,String dataset); }
 private static Repository repository(Manifest manifest) {
  return new Repository(){
   public void save(Manifest ignored){}
   public Optional<Manifest> find(UUID id,String workspace){
    return id.equals(manifest.id())&&workspace.equals(manifest.workspaceId())?Optional.of(manifest):Optional.empty();
   }
  };
 }
 private static Manifest manifest(String workspace,List<ChartSpec> charts) {
  var layout=new ArrayList<Placement>();
  for(int i=0;i<charts.size();i++) layout.add(new Placement(charts.get(i).id(),i*6,0,6,2));
  var spec=new DashboardSpec("Overview",charts,layout);
  return new Manifest(UUID.randomUUID(),UUID.randomUUID(),workspace,1,1,spec,null,"alice",Instant.EPOCH);
 }
 private static ChartSpec chart(String id,String dataset) {
  return new ChartSpec(id,Kind.BAR,id,new Binding(dataset,"region","total",null),List.of());
 }
}
