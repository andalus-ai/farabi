package tech.kayys.farabi.engine;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.SemanticQueryContracts.*;

class SemanticQueryPlanCompilerTest {
    private final List<String> resolvedScope = new ArrayList<>();

    private SemanticQueryPlanCompiler compiler() {
        return new SemanticQueryPlanCompiler((workspace, subject, dataset) -> {
            resolvedScope.add(workspace + ":" + subject + ":" + dataset);
            return new SemanticQueryPlanCompiler.Dataset("warehouse", "ansi", "sales.orders", Map.of(
                "region", new SemanticQueryPlanCompiler.Field("region", "region_name", FieldType.TEXT, true, false),
                "amount", new SemanticQueryPlanCompiler.Field("amount", "net_amount", FieldType.DECIMAL, false, true),
                "ordered_on", new SemanticQueryPlanCompiler.Field("ordered_on", "order_date", FieldType.DATE, false, false),
                "active", new SemanticQueryPlanCompiler.Field("active", "is_active", FieldType.BOOLEAN, false, false)
            ));
        }, new SemanticQueryPlanCompiler.Dialect() {
            public String id() { return "ansi"; }
            public String quoteIdentifier(String id) {
                SemanticModel.identifier(id, "identifier");
                return "\"" + id + "\"";
            }
            public String renderLimit(int limit) {
                if (limit < 1 || limit > 10000) throw new IllegalArgumentException("invalid limit");
                return "LIMIT " + limit;
            }
        });
    }

    @Test void compilesLogicalFieldsToTrustedIdentifiersAndBindsTypedValues() {
        var query = new Query("orders", List.of(new FieldRef("region")),
            List.of(new Measure(new FieldRef("amount"), Aggregation.SUM, "revenue")),
            List.of(
                new Predicate(new FieldRef("amount"), Operator.GTE, FieldType.DECIMAL, new BigDecimal("12.50")),
                new Predicate(new FieldRef("ordered_on"), Operator.LT, FieldType.DATE, LocalDate.parse("2026-01-01")),
                new Predicate(new FieldRef("active"), Operator.EQ, FieldType.BOOLEAN, true)
            ), 100);
        var plan = compiler().compile("workspace-a", "alice", query);
        assertEquals("warehouse", plan.sourceId());
        assertEquals("ansi", plan.dialectId());
        assertTrue(plan.sqlTemplate().contains("FROM \"sales\".\"orders\""));
        assertTrue(plan.sqlTemplate().contains("\"region_name\" AS \"region\""));
        assertTrue(plan.sqlTemplate().contains("SUM(\"net_amount\") AS \"revenue\""));
        assertTrue(plan.sqlTemplate().contains("GROUP BY \"region_name\""));
        assertTrue(plan.sqlTemplate().contains("\"net_amount\" >= ? AND \"order_date\" < ? AND \"is_active\" = ?"));
        assertFalse(plan.sqlTemplate().contains("12.50"));
        assertFalse(plan.sqlTemplate().contains("2026-01-01"));
        assertEquals(List.of(QueryContracts.ParameterType.DECIMAL, QueryContracts.ParameterType.DATE,
            QueryContracts.ParameterType.BOOLEAN), plan.parameters().stream().map(QueryContracts.QueryParameter::type).toList());
        assertEquals(List.of("p1", "p2", "p3"), plan.parameters().stream().map(QueryContracts.QueryParameter::name).toList());
    }

    @Test void metadataResolutionCarriesWorkspaceSubjectAndDatasetScope() {
        compiler().compile("workspace-b", "subject-7",
            new Query("orders", List.of(new FieldRef("region")), List.of(), List.of(), 5));
        assertEquals(List.of("workspace-b:subject-7:orders"), resolvedScope);
    }

    @Test void rejectsUnknownFieldsAndDialectMismatch() {
        var unknown = new Query("orders", List.of(new FieldRef("salary")), List.of(), List.of(), 5);
        assertThrows(IllegalArgumentException.class, () -> compiler().compile("ws", "user", unknown));
        var wrongDialect = new SemanticQueryPlanCompiler((w,s,d) ->
            new SemanticQueryPlanCompiler.Dataset("warehouse", "other", "sales.orders", Map.of()),
            new SemanticQueryPlanCompiler.Dialect() {
                public String id() { return "ansi"; }
                public String quoteIdentifier(String id) { return "\"" + id + "\""; }
                public String renderLimit(int limit) { return "LIMIT " + limit; }
            });
        assertThrows(IllegalArgumentException.class, () -> wrongDialect.compile("ws", "user",
            new Query("orders", List.of(new FieldRef("region")), List.of(), List.of(), 5)));
    }
}
