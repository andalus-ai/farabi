package tech.kayys.farabi.engine;

import java.util.*;
import tech.kayys.farabi.engine.DashboardManifests.*;
import tech.kayys.farabi.engine.QueryLifecycle.*;
import tech.kayys.farabi.engine.VisualizationContracts.*;

/**
 * Workspace-scoped dashboard reconstruction with caller-supplied governed data.
 * Dataset authorization is mandatory and must be implemented by the host runtime.
 */
public final class DashboardRuntime {
 public interface DatasetAccess {
  void authorize(String workspace,String subject,String datasetId);
  List<Map<String,Object>> fetch(String workspace,String subject,String datasetId,int limit);
 }
 public record ChartData(String chartId,String renderer,List<Map<String,Object>> rows) {
  public ChartData {rows=List.copyOf(rows.stream().map(Map::copyOf).toList());}
 }
 public record Rendered(RendererEnvelope.Dashboard dashboard,List<ChartData> data) {
  public Rendered {data=List.copyOf(data);}
 }
 private final Repository manifests;
 private final DatasetAccess datasets;
 public DashboardRuntime(Repository manifests,DatasetAccess datasets) {
  this.manifests=Objects.requireNonNull(manifests);this.datasets=Objects.requireNonNull(datasets);
 }
 public Rendered load(UUID manifestId,String workspace,String subject,int limit) {
  Objects.requireNonNull(manifestId);
  QueryContracts.required(workspace,"workspace");QueryContracts.required(subject,"subject");
  if(limit<1||limit>1000)throw new IllegalArgumentException("limit must be 1..1000");
  Manifest manifest=manifests.find(manifestId,workspace).orElseThrow(()->new IllegalArgumentException("manifest not found"));
  var envelope=RendererEnvelope.from(manifest);
  // Authorize every dataset before fetching any data; avoid partial disclosure.
  LinkedHashSet<String> required=new LinkedHashSet<>();
  for(var chart:envelope.charts())required.add(chart.datasetId());
  for(String dataset:required)datasets.authorize(workspace,subject,dataset);
  Map<String,List<Map<String,Object>>> fetched=new HashMap<>();
  for(String dataset:required)fetched.put(dataset,List.copyOf(datasets.fetch(workspace,subject,dataset,limit)));
  List<ChartData> charts=new ArrayList<>();
  for(var chart:envelope.charts()){
   List<Map<String,Object>> rows=fetched.get(chart.datasetId());
   // No UI filters are executed here: governed server-side predicates are required.
   charts.add(new ChartData(chart.id(),chart.renderer(),rows));
  }
  return new Rendered(envelope,charts);
 }
}
