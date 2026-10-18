package tech.kayys.andalus.bi.query;

import org.junit.jupiter.api.Test;
import tech.kayys.andalus.bi.semantic.Aggregation;
import tech.kayys.andalus.bi.semantic.SemanticMetric;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SemanticSqlCompilerTest {
    private SemanticCatalog catalog() {
        var metric = new SemanticMetric("sales.total", "Total Sales", "", "sales",
                "amount", Aggregation.SUM, "USD");
        var source = new SemanticCatalog.Source("sales", "analytics.orders",
                Map.of(metric.id(), metric), Map.of("region", "region_name"));
        return new SemanticCatalog(Map.of("sales", source));
    }

    @Test void compilesGovernedAggregationAndBoundFilter() {
        var query = new SemanticQuery("sales", List.of("sales.total"),
                List.of("region"), List.of(new SemanticQuery.Filter("region",
                SemanticQuery.Operator.EQ, "North' OR 1=1 --")), 100);
        var plan = new SemanticSqlCompiler().compile(query, catalog());
        assertTrue(plan.sql().contains("SUM(\"amount\") AS \"sales.total\""));
        assertTrue(plan.sql().contains("FROM \"analytics\".\"orders\""));
        assertTrue(plan.sql().contains("WHERE \"region_name\" = ?"));
        assertFalse(plan.sql().contains("OR 1=1"));
        assertEquals(List.of("North' OR 1=1 --", 100), plan.parameters());
    }

    @Test void rejectsUnknownDimensionsAndMetrics() {
        var compiler = new SemanticSqlCompiler();
        assertThrows(IllegalArgumentException.class, () ->
                compiler.compile(new SemanticQuery("sales", List.of("missing"),
                        List.of(), List.of(), 10), catalog()));
        assertThrows(IllegalArgumentException.class, () ->
                compiler.compile(new SemanticQuery("sales", List.of("sales.total"),
                        List.of("missing"), List.of(), 10), catalog()));
    }

    @Test void rejectsUnsafePhysicalIdentifiers() {
        var metric = new SemanticMetric("sales.total", "Sales", "", "sales",
                "amount", Aggregation.SUM, "USD");
        var bad = new SemanticCatalog(Map.of("sales", new SemanticCatalog.Source(
                "sales", "orders;DROP TABLE users", Map.of(metric.id(), metric), Map.of())));
        assertThrows(IllegalArgumentException.class, () ->
                new SemanticSqlCompiler().compile(new SemanticQuery("sales",
                        List.of("sales.total"), List.of(), List.of(), 10), bad));
    }

    @Test void rejectsUnboundedQuery() {
        assertThrows(IllegalArgumentException.class, () ->
                new SemanticQuery("sales", List.of("sales.total"), List.of(), List.of(), 0));
    }
}
