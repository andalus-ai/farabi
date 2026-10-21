package tech.kayys.farabi.engine;

import java.util.*;

/** Provider-neutral chart, layout and UI contracts. */
public final class VisualizationContracts {
    private VisualizationContracts() {}
    public enum Kind { BAR, LINE, AREA, PIE, TABLE, KPI, SCATTER }
    public record Binding(String datasetId,String categoryField,String valueField,String seriesField) {
        public Binding {
            QueryContracts.required(datasetId,"datasetId");
            QueryContracts.required(valueField,"valueField");
            if(categoryField!=null) QueryContracts.required(categoryField,"categoryField");
            if(seriesField!=null) QueryContracts.required(seriesField,"seriesField");
        }
    }
    public record Filter(String field,String operator,String value) {
        public Filter {
            QueryContracts.required(field,"field");
            if(!Set.of("eq","ne","gt","gte","lt","lte").contains(operator)) throw new IllegalArgumentException("unsupported filter operator");
            Objects.requireNonNull(value);
        }
    }
    public record ChartSpec(String id,Kind kind,String title,Binding binding,List<Filter> filters) {
        public ChartSpec {
            QueryContracts.required(id,"id");Objects.requireNonNull(kind);
            QueryContracts.required(title,"title");Objects.requireNonNull(binding);
            filters=List.copyOf(Objects.requireNonNull(filters));
        }
    }
    public record Placement(String chartId,int x,int y,int width,int height) {
        public Placement {
            QueryContracts.required(chartId,"chartId");
            if(x<0||y<0||width<1||height<1||x+width>12) throw new IllegalArgumentException("invalid grid placement");
        }
    }
    public record DashboardSpec(String title,List<ChartSpec> charts,List<Placement> layout) {
        public DashboardSpec {
            QueryContracts.required(title,"title");
            charts=List.copyOf(Objects.requireNonNull(charts));
            layout=List.copyOf(Objects.requireNonNull(layout));
            if(charts.isEmpty()) throw new IllegalArgumentException("charts required");
            Set<String> ids=new HashSet<>();
            for(ChartSpec chart:charts) if(!ids.add(chart.id())) throw new IllegalArgumentException("duplicate chart id");
            Set<String> placed=new HashSet<>();
            for(Placement item:layout) {
                if(!ids.contains(item.chartId())||!placed.add(item.chartId())) throw new IllegalArgumentException("invalid chart placement");
            }
            if(placed.size()!=ids.size()) throw new IllegalArgumentException("each chart needs one placement");
            for(int i=0;i<layout.size();i++) for(int j=i+1;j<layout.size();j++)
                if(overlaps(layout.get(i),layout.get(j))) throw new IllegalArgumentException("overlapping charts");
        }
        private static boolean overlaps(Placement a,Placement b) {
            return a.x()<b.x()+b.width() && b.x()<a.x()+a.width()
                && a.y()<b.y()+b.height() && b.y()<a.y()+a.height();
        }
    }
}
