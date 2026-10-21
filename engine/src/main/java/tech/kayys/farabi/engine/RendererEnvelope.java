package tech.kayys.farabi.engine;

import java.util.*;
import tech.kayys.farabi.engine.DashboardManifests.Manifest;
import tech.kayys.farabi.engine.VisualizationContracts.*;

/** Renderer-neutral transport model; clients decide how to instantiate Flutter widgets. */
public final class RendererEnvelope {
 private RendererEnvelope(){}
 public record Chart(String id,String renderer,String title,String datasetId,String categoryField,
                     String valueField,String seriesField,List<Filter> filters,Placement placement) {
  public Chart { filters=List.copyOf(filters); }
 }
 public record Dashboard(int schemaVersion,UUID manifestId,int revision,String workspaceId,
                         String title,List<Chart> charts) {
  public Dashboard { charts=List.copyOf(charts); }
 }
 public static Dashboard from(Manifest manifest) {
  Objects.requireNonNull(manifest);
  Map<String,Placement> placements=new HashMap<>();
  for(Placement p:manifest.spec().layout()) placements.put(p.chartId(),p);
  List<Chart> charts=new ArrayList<>();
  for(ChartSpec c:manifest.spec().charts()){
   Binding b=c.binding();
   charts.add(new Chart(c.id(),switch(c.kind()){
    case BAR -> "tenun.bar";case LINE -> "tenun.line";case AREA -> "tenun.area";
    case PIE -> "tenun.pie";case TABLE -> "farabi.table";case KPI -> "farabi.kpi";
    case SCATTER -> "tenun.scatter";
   },c.title(),b.datasetId(),b.categoryField(),b.valueField(),b.seriesField(),c.filters(),placements.get(c.id())));
  }
  return new Dashboard(1,manifest.id(),manifest.revision(),manifest.workspaceId(),manifest.spec().title(),charts);
 }
}
