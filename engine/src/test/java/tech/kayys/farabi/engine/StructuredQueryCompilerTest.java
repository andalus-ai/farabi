package tech.kayys.farabi.engine;

import java.util.List;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.StructuredQueryCompiler.Query;
import tech.kayys.farabi.engine.VisualizationContracts.Filter;
import static org.junit.jupiter.api.Assertions.*;

class StructuredQueryCompilerTest {
    private final StructuredQueryCompiler compiler = new StructuredQueryCompiler(IntelligenceFixtures.model());

    @Test void kpiHasNoGroupBy() {
        var plan = compiler.compile(new Query("orders", "revenue", List.of(), List.of(), 10));
        assertEquals("toba", plan.sourceId());
        assertEquals("SELECT SUM(\"revenue\") AS \"revenue\" FROM \"sales\".\"orders\" LIMIT 10", plan.sql());
    }

    @Test void categoryBreakdownOrdersByMeasureDescending() {
        var sql = compiler.compile(new Query("orders", "revenue", List.of("region"), List.of(), 500)).sql();
        assertEquals("SELECT \"region\" AS \"region\", SUM(\"revenue\") AS \"revenue\" FROM \"sales\".\"orders\" "
            + "GROUP BY \"region\" ORDER BY SUM(\"revenue\") DESC, \"region\" ASC LIMIT 500", sql);
    }

    @Test void timeSeriesOrdersChronologicallyAndMapsColumnNames() {
        var sql = compiler.compile(new Query("orders", "revenue", List.of("month", "channel"), List.of(), 500)).sql();
        assertTrue(sql.contains("\"order_month\" AS \"month\", \"sales_channel\" AS \"channel\""));
        assertTrue(sql.contains("ORDER BY \"order_month\" ASC, \"sales_channel\" ASC"));
    }

    @Test void filtersAreTypedAndLiteralsAreSafe() {
        var sql = compiler.compile(new Query("orders", "revenue", List.of(),
            List.of(new Filter("region", "eq", "APAC"), new Filter("units", "gte", "10.5"), new Filter("month", "lt", "2026-03-01")), 5)).sql();
        assertTrue(sql.contains("WHERE \"region\" = 'APAC' AND \"units\" >= 10.5 AND \"order_month\" < DATE '2026-03-01'"));
    }

    @Test void rejectsInjectionAndTypeMismatches() {
        for (String bad : List.of("x' OR '1'='1", "a;DROP TABLE t", "APAC\"", "")) {
            assertThrows(IllegalArgumentException.class, () -> compiler.compile(
                new Query("orders", "revenue", List.of(), List.of(new Filter("region", "eq", bad)), 5)), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(new Query("orders", "revenue", List.of(),
            List.of(new Filter("units", "eq", "1 OR 1=1")), 5)));
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(new Query("orders", "revenue", List.of(),
            List.of(new Filter("month", "eq", "yesterday")), 5)));
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(new Query("orders", "revenue", List.of(),
            List.of(new Filter("region", "gt", "A")), 5)));
    }

    @Test void rejectsUnknownFieldsAndBadLimits() {
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(new Query("nope", "revenue", List.of(), List.of(), 5)));
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(new Query("orders", "salary", List.of(), List.of(), 5)));
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(new Query("orders", "revenue", List.of("department"), List.of(), 5)));
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(new Query("orders", "revenue", List.of(), List.of(new Filter("revenue", "eq", "1")), 5)));
        assertThrows(IllegalArgumentException.class, () -> new Query("orders", "revenue", List.of(), List.of(), 1001));
        assertThrows(IllegalArgumentException.class, () -> new Query("orders", "revenue", List.of("region", "region"), List.of(), 5));
    }

    @Test void enforcesMaximumRowsAndDimensionalityAtTheBoundary() {
        var maximum = compiler.compile(new Query("orders", "revenue", List.of("region", "channel"), List.of(), StructuredQueryCompiler.MAX_LIMIT));
        assertTrue(maximum.sql().endsWith("LIMIT " + StructuredQueryCompiler.MAX_LIMIT));
        assertThrows(IllegalArgumentException.class,
            () -> new Query("orders", "revenue", List.of("region", "channel", "month"), List.of(), 10));
        assertThrows(IllegalArgumentException.class,
            () -> new Query("orders", "revenue", List.of(), List.of(), StructuredQueryCompiler.MAX_LIMIT + 1));
    }

    @Test void chartCompilationUsesTheBoundedDefaultRowLimit() {
        var chart = new tech.kayys.farabi.engine.VisualizationContracts.ChartSpec("kpi", 
            tech.kayys.farabi.engine.VisualizationContracts.Kind.KPI, "Revenue",
            new tech.kayys.farabi.engine.VisualizationContracts.Binding("orders", null, "revenue", null), List.of());
        var plan = compiler.compile(Query.fromChart(chart, StructuredQueryCompiler.DEFAULT_LIMIT));
        assertTrue(plan.sql().endsWith("LIMIT " + StructuredQueryCompiler.DEFAULT_LIMIT));
    }

    @Test void categoryFilterTextHasAnExplicitLengthCeiling() {
        String maximum = "a".repeat(64);
        assertDoesNotThrow(() -> compiler.compile(new Query("orders", "revenue", List.of(),
            List.of(new Filter("region", "eq", maximum)), 10)));
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(new Query("orders", "revenue", List.of(),
            List.of(new Filter("region", "eq", maximum + "a")), 10)));
    }

    @Test void rejectsQueriesWithMoreThanTheSupportedFilterCount() {
        List<Filter> maximum = java.util.stream.IntStream.range(0, StructuredQueryCompiler.MAX_FILTERS)
            .mapToObj(i -> new Filter("region", "eq", "R" + i)).toList();
        assertDoesNotThrow(() -> new Query("orders", "revenue", List.of(), maximum, 10));
        List<Filter> excessive = new java.util.ArrayList<>(maximum);
        excessive.add(new Filter("region", "eq", "overflow"));
        assertThrows(IllegalArgumentException.class,
            () -> new Query("orders", "revenue", List.of(), excessive, 10));
    }

    @Test void catalogRejectsUnsafeIdentifiers() {
        assertThrows(IllegalArgumentException.class, () -> new SemanticModel.Measure("m", "M", "revenue); DROP", SemanticModel.Aggregation.SUM));
        assertThrows(IllegalArgumentException.class, () -> new SemanticModel.Dataset("d", "D", "s", "a.b-c", List.of(IntelligenceFixtures.ORDERS.measures().get(0)), List.of()));
    }
}
