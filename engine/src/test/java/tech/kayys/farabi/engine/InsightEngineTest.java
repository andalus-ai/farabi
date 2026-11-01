package tech.kayys.farabi.engine;

import java.util.*;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.InsightEngine.*;
import tech.kayys.farabi.engine.VisualizationContracts.*;
import static org.junit.jupiter.api.Assertions.*;

class InsightEngineTest {
    private final InsightEngine engine = new InsightEngine();
    private static ChartSpec chart(Kind k, String cat) { return new ChartSpec("c", k, "t", new Binding("orders", cat, "revenue", null), List.of()); }
    private static Map<String, Object> row(String cat, Object v, String field) { Map<String, Object> m = new LinkedHashMap<>(); m.put(field, cat); m.put("revenue", v); return m; }
    private static Set<Type> types(List<Insight> l) { Set<Type> s = new TreeSet<>(); l.forEach(i -> s.add(i.type())); return s; }

    @Test void emptyDataIsFlagged() {
        var out = engine.analyze(chart(Kind.BAR, "region"), List.of());
        assertEquals(Type.EMPTY, out.get(0).type());
    }

    @Test void topContributorAndConcentration() {
        List<Map<String, Object>> rows = new ArrayList<>();
        double[] v = {600, 250, 100, 30, 20};
        for (int i = 0; i < v.length; i++) rows.add(row("R" + i, v[i], "region"));
        var out = engine.analyze(chart(Kind.BAR, "region"), rows);
        var top = out.stream().filter(i -> i.type() == Type.TOP_CONTRIBUTOR).findFirst().orElseThrow();
        assertEquals("R0 contributes 60.0% of revenue", top.headline());
        assertEquals(60.0, top.evidence().get("sharePercent"));
        assertTrue(types(out).contains(Type.CONCENTRATION));
    }

    @Test void latestChangeAndTrendOnTimeSeries() {
        List<Map<String, Object>> rows = new ArrayList<>();
        double[] v = {100, 105, 110, 108, 190};
        for (int i = 0; i < v.length; i++) rows.add(row("2026-0" + (i + 1), v[i], "month"));
        var out = engine.analyze(chart(Kind.LINE, "month"), rows);
        var change = out.stream().filter(i -> i.type() == Type.LATEST_CHANGE).findFirst().orElseThrow();
        assertEquals("revenue rose 75.9% at 2026-05 versus 2026-04", change.headline());
        assertEquals(Severity.NOTICE, change.severity());
        assertTrue(types(out).contains(Type.TREND));
    }

    @Test void robustOutlierDetectionIgnoresNormalSpread() {
        List<Map<String, Object>> flat = new ArrayList<>();
        double[] v = {10, 11, 9, 10, 12, 11};
        for (int i = 0; i < v.length; i++) flat.add(row("c" + i, v[i], "region"));
        assertFalse(types(engine.analyze(chart(Kind.BAR, "region"), flat)).contains(Type.OUTLIER));
        flat.add(row("spike", 500.0, "region"));
        var out = engine.analyze(chart(Kind.BAR, "region"), flat);
        assertEquals("spike", out.stream().filter(i -> i.type() == Type.OUTLIER).findFirst().orElseThrow().evidence().get("category"));
    }

    @Test void kpiAndNonNumericValuesAreHandledSafely() {
        assertTrue(engine.analyze(chart(Kind.KPI, null), List.of(Map.of("revenue", 1.0))).isEmpty());
        var rows = List.of(row("a", "n/a", "region"), row("b", null, "region"));
        assertTrue(engine.analyze(chart(Kind.BAR, "region"), rows).isEmpty());
    }
}
