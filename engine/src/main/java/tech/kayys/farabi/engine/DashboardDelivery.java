package tech.kayys.farabi.engine;

import java.util.*;

/** JSON-friendly dashboard delivery contract, independent of Flutter and HTTP. */
public final class DashboardDelivery {
 private DashboardDelivery(){}
 public enum State { READY, EMPTY }
 public record Chart(String id,String renderer,String title,
                     VisualizationContracts.Placement placement,
                     VisualizationContracts.Binding binding,
                     List<Map<String,Object>> rows,State state) {
  public Chart {
   Objects.requireNonNull(id);Objects.requireNonNull(renderer);Objects.requireNonNull(title);
   Objects.requireNonNull(placement);Objects.requireNonNull(binding);Objects.requireNonNull(state);
   rows=List.copyOf(Objects.requireNonNull(rows).stream()
       .map(row->Collections.unmodifiableMap(new LinkedHashMap<>(row))).toList());
  }
 }
 public record Document(int schemaVersion,UUID manifestId,int revision,String title,List<Chart> charts) {
  public Document {charts=List.copyOf(Objects.requireNonNull(charts));}
 }
 public static Document from(DashboardRuntime.Rendered rendered) {
  Objects.requireNonNull(rendered);
  var dashboard=rendered.dashboard();
  Map<String,DashboardRuntime.ChartData> data=new HashMap<>();
  for(var item:rendered.data()) if(data.putIfAbsent(item.chartId(),item)!=null)
   throw new IllegalArgumentException("duplicate chart data");
  List<Chart> charts=new ArrayList<>();
  for(var chart:dashboard.charts()){
   var values=data.remove(chart.id());
   if(values==null||!chart.renderer().equals(values.renderer()))
    throw new IllegalArgumentException("missing or mismatched chart data");
   var binding=new VisualizationContracts.Binding(chart.datasetId(),chart.categoryField(),
       chart.valueField(),chart.seriesField());
   charts.add(new Chart(chart.id(),chart.renderer(),chart.title(),chart.placement(),binding,
       values.rows(),values.rows().isEmpty()?State.EMPTY:State.READY));
  }
  if(!data.isEmpty())throw new IllegalArgumentException("unrecognized chart data");
  return new Document(1,dashboard.manifestId(),dashboard.revision(),dashboard.title(),charts);
 }
}
