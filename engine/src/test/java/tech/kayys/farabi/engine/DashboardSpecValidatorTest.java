package tech.kayys.farabi.engine;

import java.util.*;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.VisualizationContracts.*;
import static org.junit.jupiter.api.Assertions.*;

class DashboardSpecValidatorTest {
    private final Set<PolicyAuthorizer.Grant> grants = IntelligenceFixtures.grants("ws", "alice", "orders");
    private final DashboardSpecValidator validator = new DashboardSpecValidator(IntelligenceFixtures.model(), IntelligenceFixtures.policy(grants));

    private static DashboardSpec spec(ChartSpec... charts) {
        return new DashboardSpec("T", List.of(charts), DashboardLayouts.flow(List.of(charts)));
    }
    private static ChartSpec chart(String id, Kind k, String ds, String cat, String val, String series) {
        return new ChartSpec(id, k, "title " + id, new Binding(ds, cat, val, series), List.of());
    }
    private static Set<String> codes(DashboardSpecValidator.Result r) {
        Set<String> s = new TreeSet<>(); r.issues().forEach(i -> s.add(i.code())); return s;
    }

    @Test void acceptsWellFormedSpec() {
        var r = validator.validate("ws", "alice", spec(
            chart("k", Kind.KPI, "orders", null, "revenue", null),
            chart("t", Kind.LINE, "orders", "month", "revenue", "region"),
            chart("b", Kind.BAR, "orders", "region", "revenue", null),
            chart("p", Kind.PIE, "orders", "channel", "order_count", null)));
        assertTrue(r.valid(), r.issues().toString());
    }

    @Test void forbiddenAndMissingDatasetsAreIndistinguishable() {
        var forbidden = validator.validate("ws", "alice", spec(chart("a", Kind.KPI, "payroll", null, "salary", null)));
        var missing = validator.validate("ws", "alice", spec(chart("a", Kind.KPI, "ghost", null, "salary", null)));
        assertEquals(forbidden, missing);
        assertEquals(Set.of("DATASET_UNAVAILABLE"), codes(forbidden));
        var otherWorkspace = validator.validate("other", "alice", spec(chart("a", Kind.KPI, "orders", null, "revenue", null)));
        assertEquals(Set.of("DATASET_UNAVAILABLE"), codes(otherWorkspace));
    }

    @Test void reportsAllProblemsAtOnce() {
        var r = validator.validate("ws", "alice", spec(
            chart("a", Kind.BAR, "orders", "department", "salary", null),
            chart("b", Kind.LINE, "orders", "region", "revenue", null),
            chart("c", Kind.PIE, "orders", "region", "revenue", "channel"),
            chart("d", Kind.KPI, "orders", "region", "revenue", null),
            chart("e", Kind.BAR, "orders", null, "revenue", null)));
        assertEquals(Set.of("UNKNOWN_MEASURE", "UNKNOWN_DIMENSION", "KIND_MISMATCH"), codes(r));
        assertEquals(Set.of("a", "b", "c", "d", "e"), new TreeSet<>(r.issues().stream().map(DashboardSpecValidator.Issue::chartId).toList()));
        assertThrows(DashboardSpecValidator.InvalidSpecException.class, r::requireValid);
    }

    @Test void catchesBadFilterValuesViaTheCompiler() {
        var bad = new ChartSpec("a", Kind.KPI, "t", new Binding("orders", null, "revenue", null), List.of(new Filter("region", "eq", "x' OR 1=1")));
        assertEquals(Set.of("INVALID_QUERY"), codes(validator.validate("ws", "alice", new DashboardSpec("T", List.of(bad), DashboardLayouts.flow(List.of(bad))))));
    }

    @Test void boundsChartCountAndTitles() {
        List<ChartSpec> many = new ArrayList<>();
        for (int i = 0; i < 13; i++) many.add(chart("c" + i, Kind.KPI, "orders", null, "revenue", null));
        var r = validator.validate("ws", "alice", new DashboardSpec("T", many, DashboardLayouts.flow(many)));
        assertTrue(codes(r).contains("TOO_MANY_CHARTS"));
        var longTitle = new ChartSpec("a", Kind.KPI, "x".repeat(121), new Binding("orders", null, "revenue", null), List.of());
        assertTrue(codes(validator.validate("ws", "alice", spec(longTitle))).contains("TITLE_TOO_LONG"));
    }

    @Test void acceptsTheMaximumSupportedChartCount() {
        List<ChartSpec> maximum = new ArrayList<>();
        for (int i = 0; i < DashboardSpecValidator.MAX_CHARTS; i++)
            maximum.add(chart("c" + i, Kind.KPI, "orders", null, "revenue", null));
        var result = validator.validate("ws", "alice", new DashboardSpec("T", maximum, DashboardLayouts.flow(maximum)));
        assertTrue(result.valid(), result.issues().toString());
    }

    @Test void rejectsChartsWithExcessiveFilterPredicates() {
        List<Filter> filters = java.util.stream.IntStream.range(0, StructuredQueryCompiler.MAX_FILTERS + 1)
            .mapToObj(i -> new Filter("region", "eq", "R" + i)).toList();
        var excessive = new ChartSpec("many-filters", Kind.KPI, "Revenue",
            new Binding("orders", null, "revenue", null), filters);
        var result = validator.validate("ws", "alice", spec(excessive));
        assertTrue(codes(result).contains("TOO_MANY_FILTERS"));
        assertFalse(result.valid());
    }

    @Test void layoutNeverOverlapsForAnyMix() {
        List<ChartSpec> mix = new ArrayList<>();
        Kind[] kinds = {Kind.KPI, Kind.KPI, Kind.BAR, Kind.TABLE, Kind.KPI, Kind.LINE, Kind.PIE, Kind.BAR, Kind.KPI, Kind.TABLE};
        for (int i = 0; i < kinds.length; i++) mix.add(chart("c" + i, kinds[i], "orders", "region", "revenue", null));
        assertDoesNotThrow(() -> new DashboardSpec("T", mix, DashboardLayouts.flow(mix)));
    }
}
