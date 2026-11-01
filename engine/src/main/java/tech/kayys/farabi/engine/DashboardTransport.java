package tech.kayys.farabi.engine;

import java.util.*;

/** JSON-friendly, versioned transport for Batik clients without Flutter dependencies. */
public final class DashboardTransport {
 private DashboardTransport(){}
 public record Error(String code,String message) {
  public Error {QueryContracts.required(code,"code");QueryContracts.required(message,"message");}
 }
 public record ChartPayload(String id,String renderer,String title,RendererEnvelope.Chart definition,
                            List<Map<String,Object>> rows) {
  public ChartPayload {
   Objects.requireNonNull(definition);
   rows=List.copyOf(Objects.requireNonNull(rows).stream()
       .map(row->Collections.unmodifiableMap(new LinkedHashMap<>(row))).toList());
  }
 }
 /** Version 1 with an additive {@code insights} array; clients that ignore unknown fields are unaffected. */
 public record Document(int version,String status,UUID manifestId,int revision,String title,
                        List<ChartPayload> charts,Error error,List<InsightEngine.Insight> insights) {
  public Document(int version,String status,UUID manifestId,int revision,String title,List<ChartPayload> charts,Error error) {
   this(version,status,manifestId,revision,title,charts,error,List.of());
  }
  public Document {
   if(version!=1)throw new IllegalArgumentException("unsupported document version");
   if(!Set.of("ready","error","loading").contains(status))throw new IllegalArgumentException("invalid status");
   charts=List.copyOf(Objects.requireNonNull(charts));
   insights=List.copyOf(Objects.requireNonNull(insights));
   if(status.equals("error")&&error==null)throw new IllegalArgumentException("error details required");
  }
 }
 public static Document ready(DashboardRuntime.Rendered rendered){
  return ready(rendered,List.of());
 }
 public static Document ready(DashboardRuntime.Rendered rendered,List<InsightEngine.Insight> insights){
  Objects.requireNonNull(rendered);
  var dashboard=rendered.dashboard();
  Map<String,DashboardRuntime.ChartData> data=new HashMap<>();
  for(var item:rendered.data())data.put(item.chartId(),item);
  List<ChartPayload> charts=new ArrayList<>();
  for(var chart:dashboard.charts()){
   var item=Objects.requireNonNull(data.get(chart.id()),"missing chart data");
   charts.add(new ChartPayload(chart.id(),chart.renderer(),chart.title(),chart,item.rows()));
  }
  return new Document(1,"ready",dashboard.manifestId(),dashboard.revision(),dashboard.title(),charts,null,insights);
 }
 public static Document loading(UUID manifestId){
  return new Document(1,"loading",Objects.requireNonNull(manifestId),0,"",List.of(),null);
 }
 public static Document error(UUID manifestId,String code,String message){
  return new Document(1,"error",Objects.requireNonNull(manifestId),0,"",List.of(),new Error(code,message));
 }
}
