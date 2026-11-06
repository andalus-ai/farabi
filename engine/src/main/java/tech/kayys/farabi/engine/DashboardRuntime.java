package tech.kayys.farabi.engine;

import java.util.*;
import tech.kayys.farabi.engine.DashboardManifests.*;

/**
 * Workspace-scoped dashboard reconstruction with caller-supplied governed data.
 * Dataset authorization is mandatory and must be implemented by the host runtime.
 */
public final class DashboardRuntime {
 public interface DatasetAccess {
  void authorize(String workspace,String subject,String datasetId);
  List<Map<String,Object>> fetch(String workspace,String subject,String datasetId,int limit);
  /** Execute chart predicates in the trusted data adapter; never silently drop filters. */
  default List<Map<String,Object>> fetch(String workspace,String subject,String datasetId,
      List<VisualizationContracts.Filter> filters,int limit) {
   if(!filters.isEmpty()) throw new UnsupportedOperationException(
       "dataset adapter must implement server-side chart filters");
   return fetch(workspace,subject,datasetId,limit);
  }
 }
 public record ChartData(String chartId,String renderer,List<Map<String,Object>> rows) {
  public ChartData {rows=List.copyOf(Objects.requireNonNull(rows).stream().map(row -> Collections.unmodifiableMap(new LinkedHashMap<>(row))).toList());}
 }
 public record Rendered(RendererEnvelope.Dashboard dashboard,List<ChartData> data) {
  public Rendered {data=List.copyOf(data);}
 }
 private record FetchKey(String datasetId,List<VisualizationContracts.Filter> filters) {}
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
  Map<FetchKey,List<Map<String,Object>>> fetched=new HashMap<>();
  List<ChartData> charts=new ArrayList<>();
  for(var chart:envelope.charts()){
   FetchKey key=new FetchKey(chart.datasetId(),chart.filters());
   List<Map<String,Object>> rows=fetched.computeIfAbsent(key,ignored -> List.copyOf(
       Objects.requireNonNull(datasets.fetch(workspace,subject,chart.datasetId(),chart.filters(),limit))));
   charts.add(new ChartData(chart.id(),chart.renderer(),rows));
  }
  return new Rendered(envelope,charts);
 }
}
