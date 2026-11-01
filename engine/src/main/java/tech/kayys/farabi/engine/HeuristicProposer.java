package tech.kayys.farabi.engine;

import java.util.*;
import java.util.regex.*;
import tech.kayys.farabi.engine.DashboardPlanner.*;
import tech.kayys.farabi.engine.SemanticModel.*;
import tech.kayys.farabi.engine.VisualizationContracts.*;

/**
 * Deterministic, offline baseline proposer: maps request words onto catalog labels and picks chart kinds
 * from intent words (trend, share, table...). It is the reference behaviour for LLM-backed proposers and
 * a safe fallback; every assumption it makes is reported back to the user.
 */
public final class HeuristicProposer implements Proposer {
    private static final Set<String> TREND = Set.of("trend", "over", "time", "monthly", "daily", "weekly", "yearly", "timeline", "growth", "history");
    private static final Set<String> SHARE = Set.of("share", "breakdown", "split", "distribution", "mix", "composition", "proportion");
    private static final Set<String> TABLE = Set.of("table", "list", "detail", "details", "rows");
    private static final Set<String> STOP = Set.of("a", "an", "the", "of", "by", "per", "for", "in", "on", "and", "show", "me", "give", "my", "with", "to", "is", "are", "what", "how", "much", "many", "dashboard", "chart");
    private static final Pattern FILTER = Pattern.compile("([A-Za-z_][A-Za-z_0-9]*)\\s*(?:=|\\bis\\b|\\bequals\\b)\\s*([A-Za-z0-9_.\\-]+)", Pattern.CASE_INSENSITIVE);
    private static final int MAX_CHARTS = 6;

    @Override public Proposal propose(Request request) {
        Set<String> words = stems(request.prompt());
        List<String> ordered = new ArrayList<>(words);   // insertion order = order of first mention
        List<String> assumptions = new ArrayList<>();

        Dataset ds = request.catalog().datasets().stream()
            .max(Comparator.comparingInt((Dataset d) -> score(d, words)).thenComparing(Dataset::id, Comparator.reverseOrder()))
            .filter(d -> score(d, words) > 0)
            .orElseThrow(() -> new IllegalArgumentException("the request does not match any available dataset"));

        // Filters first, so a filtered dimension is not also charted as a breakdown.
        List<Filter> filters = new ArrayList<>();
        Set<String> filtered = new HashSet<>();
        Matcher fm = FILTER.matcher(request.prompt());
        while (fm.find()) {
            Optional<Dimension> d = ds.dimensions().stream().filter(x -> x.type() == DimensionType.CATEGORY
                && (x.id().equalsIgnoreCase(fm.group(1)) || x.label().equalsIgnoreCase(fm.group(1)))).findFirst();
            if (d.isPresent() && filtered.add(d.get().id())) {
                filters.add(new Filter(d.get().id(), "eq", fm.group(2)));
                assumptions.add("Filtering " + d.get().label() + " = " + fm.group(2));
            }
        }

        // The measure the user mentions first is the primary one, regardless of how many words its label has.
        List<Measure> measures = ds.measures().stream().filter(m -> overlap(m.id(), m.label(), words) > 0)
            .sorted(Comparator.comparingInt((Measure m) -> firstMention(m.id(), m.label(), ordered))).toList();
        if (measures.isEmpty()) {
            measures = List.of(ds.measures().get(0));
            assumptions.add("No measure named in the request; using " + measures.get(0).label());
        }
        Measure primary = measures.get(0);

        boolean trendWanted = words.stream().anyMatch(TREND::contains);
        boolean shareWanted = words.stream().anyMatch(SHARE::contains);
        boolean tableWanted = words.stream().anyMatch(TABLE::contains);

        List<Dimension> matched = ds.dimensions().stream()
            .filter(d -> !filtered.contains(d.id()) && overlap(d.id(), d.label(), words) > 0).toList();
        Dimension time = matched.stream().filter(d -> d.type() == DimensionType.TIME).findFirst().orElse(null);
        List<Dimension> breakdowns = matched.stream().filter(d -> d.type() != DimensionType.TIME).limit(2).toList();
        if (time == null && trendWanted) {
            time = ds.dimensions().stream().filter(d -> d.type() == DimensionType.TIME).findFirst().orElse(null);
            if (time != null) assumptions.add("Trend requested; using time dimension " + time.label());
        }
        if (time == null && breakdowns.isEmpty() && !tableWanted) {
            Optional<Dimension> fallback = ds.dimensions().stream()
                .filter(d -> d.type() == DimensionType.CATEGORY && !filtered.contains(d.id())).findFirst();
            if (fallback.isPresent()) {
                breakdowns = List.of(fallback.get());
                assumptions.add("No dimension named in the request; breaking down by " + fallback.get().label());
            }
        }

        List<ChartSpec> charts = new ArrayList<>();
        charts.add(chart("kpi_" + primary.id(), Kind.KPI, "Total " + primary.label(), ds, null, primary, null, filters));
        for (Measure extra : measures.stream().skip(1).limit(2).toList())
            charts.add(chart("kpi_" + extra.id(), Kind.KPI, "Total " + extra.label(), ds, null, extra, null, filters));
        if (time != null) {
            Dimension split = trendWanted ? breakdowns.stream().filter(d -> d.type() == DimensionType.CATEGORY).findFirst().orElse(null) : null;
            charts.add(chart("trend_" + primary.id() + "_" + time.id(), Kind.LINE, primary.label() + " over " + time.label(), ds, time, primary, split, filters));
        }
        for (Dimension d : breakdowns) {
            boolean pie = shareWanted && d.type() == DimensionType.CATEGORY;
            charts.add(chart((pie ? "pie_" : "bar_") + primary.id() + "_" + d.id(), pie ? Kind.PIE : Kind.BAR,
                primary.label() + " by " + d.label(), ds, d, primary, null, filters));
        }
        if (tableWanted) {
            Dimension first = !breakdowns.isEmpty() ? breakdowns.get(0) : null;
            charts.add(chart("table_" + primary.id(), Kind.TABLE, primary.label() + " detail", ds, first, primary, null, filters));
        }
        if (charts.size() > MAX_CHARTS) charts = new ArrayList<>(charts.subList(0, MAX_CHARTS));

        DashboardSpec spec = new DashboardSpec(ds.label() + " — " + primary.label() + " overview", charts, DashboardLayouts.flow(charts));
        return new Proposal(spec, assumptions, "heuristic-v1");
    }

    private static ChartSpec chart(String id, Kind kind, String title, Dataset ds, Dimension category, Measure value, Dimension series, List<Filter> filters) {
        return new ChartSpec(id, kind, title,
            new Binding(ds.id(), category == null ? null : category.id(), value.id(), series == null ? null : series.id()), filters);
    }

    private static int score(Dataset d, Set<String> words) {
        int s = overlap(d.id(), d.label(), words);
        for (Measure m : d.measures()) s += overlap(m.id(), m.label(), words);
        for (Dimension x : d.dimensions()) s += overlap(x.id(), x.label(), words);
        return s;
    }

    private static int overlap(String id, String label, Set<String> words) {
        Set<String> own = stems(id.replace('_', ' ') + " " + label);
        own.retainAll(words);
        return own.size();
    }

    private static int firstMention(String id, String label, List<String> ordered) {
        Set<String> own = stems(id.replace('_', ' ') + " " + label);
        for (int i = 0; i < ordered.size(); i++) if (own.contains(ordered.get(i))) return i;
        return Integer.MAX_VALUE;
    }

    private static Set<String> stems(String text) {
        Set<String> out = new LinkedHashSet<>();
        for (String t : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (t.length() < 3 || STOP.contains(t)) continue;
            out.add(t.length() > 3 && t.endsWith("s") ? t.substring(0, t.length() - 1) : t);
        }
        return out;
    }
}
