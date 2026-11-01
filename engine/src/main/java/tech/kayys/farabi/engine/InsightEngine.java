package tech.kayys.farabi.engine;

import java.util.*;
import tech.kayys.farabi.engine.VisualizationContracts.*;

/**
 * Deterministic, explainable insights computed from the rows a chart actually returned.
 * Every insight carries its numeric evidence, so a language model may narrate it later but never has to invent it.
 */
public final class InsightEngine {
    public enum Type { EMPTY, TOP_CONTRIBUTOR, CONCENTRATION, TREND, LATEST_CHANGE, OUTLIER }
    public enum Severity { INFO, NOTICE }

    public record Insight(String chartId, Type type, Severity severity, String headline, Map<String, Object> evidence) {
        public Insight { evidence = Collections.unmodifiableMap(new LinkedHashMap<>(evidence)); }
    }

    public List<Insight> analyze(ChartSpec chart, List<Map<String, Object>> rows) {
        List<Insight> out = new ArrayList<>();
        if (rows.isEmpty()) {
            out.add(new Insight(chart.id(), Type.EMPTY, Severity.NOTICE, "No data matched this chart", Map.of()));
            return out;
        }
        Binding b = chart.binding();
        if (chart.kind() == Kind.KPI || b.categoryField() == null) return out;

        Map<String, Double> byCategory = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Double v = number(row.get(b.valueField()));
            if (v == null) continue;
            byCategory.merge(String.valueOf(row.get(b.categoryField())), v, Double::sum);
        }
        if (byCategory.isEmpty()) return out;
        List<String> labels = new ArrayList<>(byCategory.keySet());
        double[] values = byCategory.values().stream().mapToDouble(Double::doubleValue).toArray();
        String measure = b.valueField();

        if (chart.kind() == Kind.LINE || chart.kind() == Kind.AREA) trend(chart, measure, labels, values, out);
        else share(chart, measure, labels, values, out);
        outliers(chart, labels, values, out);
        return out;
    }

    private void trend(ChartSpec chart, String measure, List<String> labels, double[] v, List<Insight> out) {
        int n = v.length;
        if (n < 2) return;
        double last = v[n - 1], prev = v[n - 2];
        if (prev != 0) {
            double change = (last - prev) / Math.abs(prev) * 100;
            if (Math.abs(change) >= 10)
                out.add(new Insight(chart.id(), Type.LATEST_CHANGE, Math.abs(change) >= 25 ? Severity.NOTICE : Severity.INFO,
                    measure + (change > 0 ? " rose " : " fell ") + pct(Math.abs(change)) + " at " + labels.get(n - 1) + " versus " + labels.get(n - 2),
                    ev("latest", last, "previous", prev, "changePercent", round(change))));
        }
        if (n >= 4) {
            double meanX = (n - 1) / 2.0, meanY = Arrays.stream(v).average().orElse(0), num = 0, den = 0;
            for (int i = 0; i < n; i++) { num += (i - meanX) * (v[i] - meanY); den += (i - meanX) * (i - meanX); }
            double drift = meanY == 0 ? 0 : (num / den) * (n - 1) / Math.abs(meanY) * 100;
            if (Math.abs(drift) >= 10)
                out.add(new Insight(chart.id(), Type.TREND, Severity.INFO,
                    measure + " is trending " + (drift > 0 ? "up" : "down") + " about " + pct(Math.abs(drift)) + " across the period",
                    ev("points", n, "driftPercent", round(drift))));
        }
    }

    private void share(ChartSpec chart, String measure, List<String> labels, double[] v, List<Insight> out) {
        int n = v.length;
        double total = Arrays.stream(v).sum();
        if (n < 2 || total <= 0 || Arrays.stream(v).anyMatch(x -> x < 0)) return;
        int top = 0;
        for (int i = 1; i < n; i++) if (v[i] > v[top]) top = i;
        double share = v[top] / total * 100;
        out.add(new Insight(chart.id(), Type.TOP_CONTRIBUTOR, Severity.INFO,
            labels.get(top) + " contributes " + pct(share) + " of " + measure, ev("category", labels.get(top), "value", v[top], "sharePercent", round(share))));
        if (n > 4) {
            double top3 = Arrays.stream(v).boxed().sorted(Comparator.reverseOrder()).limit(3).mapToDouble(Double::doubleValue).sum() / total * 100;
            if (top3 >= 80)
                out.add(new Insight(chart.id(), Type.CONCENTRATION, Severity.NOTICE,
                    "Top 3 of " + n + " categories account for " + pct(top3) + " of " + measure, ev("categories", n, "top3SharePercent", round(top3))));
        }
    }

    /** Robust outliers: modified z-score from median and MAD, so one extreme point cannot hide itself. */
    private void outliers(ChartSpec chart, List<String> labels, double[] v, List<Insight> out) {
        if (v.length < 5) return;
        double median = median(v);
        double[] dev = Arrays.stream(v).map(x -> Math.abs(x - median)).toArray();
        double mad = median(dev);
        if (mad == 0) return;
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < v.length; i++) if (Math.abs(0.6745 * (v[i] - median) / mad) > 3.5) idx.add(i);
        idx.sort(Comparator.comparingDouble((Integer i) -> Math.abs(v[i] - median)).reversed());
        for (int i : idx.stream().limit(3).toList())
            out.add(new Insight(chart.id(), Type.OUTLIER, Severity.NOTICE,
                labels.get(i) + " is unusual for " + chart.binding().valueField() + " (" + round(v[i]) + " vs median " + round(median) + ")",
                ev("category", labels.get(i), "value", v[i], "median", median, "score", round(0.6745 * (v[i] - median) / mad))));
    }

    private static Map<String, Object> ev(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
    private static Double number(Object o) {
        if (o instanceof Number n) return Double.isFinite(n.doubleValue()) ? n.doubleValue() : null;
        if (o instanceof String s) { try { double d = Double.parseDouble(s); return Double.isFinite(d) ? d : null; } catch (NumberFormatException e) { return null; } }
        return null;
    }
    private static double median(double[] x) {
        double[] s = x.clone(); Arrays.sort(s);
        return s.length % 2 == 1 ? s[s.length / 2] : (s[s.length / 2 - 1] + s[s.length / 2]) / 2;
    }
    private static double round(double d) { return Math.round(d * 100.0) / 100.0; }
    private static String pct(double d) { return String.format(Locale.ROOT, "%.1f%%", d); }
}
