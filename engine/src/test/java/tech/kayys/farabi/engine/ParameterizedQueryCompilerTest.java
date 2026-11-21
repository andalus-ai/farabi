package tech.kayys.farabi.engine;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.StructuredQueryCompiler.Query;
import tech.kayys.farabi.engine.VisualizationContracts.Filter;
import static org.junit.jupiter.api.Assertions.*;

class ParameterizedQueryCompilerTest {
    private final StructuredQueryCompiler compiler = new StructuredQueryCompiler(IntelligenceFixtures.model());

    @Test void filterValuesBecomeTypedParametersAndNeverSqlLiterals() {
        var plan = compiler.compileParameterized(new Query("orders", "revenue", List.of(),
            List.of(new Filter("region", "eq", "O'Reilly"),
                new Filter("units", "gte", "10.50"),
                new Filter("month", "lt", "2026-03-01")), 25));
        assertEquals("toba", plan.sourceId());
        assertEquals("ansi", plan.dialectId());
        assertTrue(plan.sqlTemplate().contains("\"region\" = ? AND \"units\" >= ? AND \"order_month\" < ?"));
        assertFalse(plan.sqlTemplate().contains("O'Reilly"));
        assertFalse(plan.sqlTemplate().contains("10.50"));
        assertFalse(plan.sqlTemplate().contains("2026-03-01"));
        assertTrue(plan.sqlTemplate().endsWith("LIMIT 25"));
        assertEquals(List.of("p1", "p2", "p3"), plan.parameters().stream().map(QueryContracts.QueryParameter::name).toList());
        assertEquals("O'Reilly", plan.parameters().get(0).value());
        assertEquals(new BigDecimal("10.50"), plan.parameters().get(1).value());
        assertEquals(LocalDate.parse("2026-03-01"), plan.parameters().get(2).value());
        assertEquals(QueryContracts.ParameterType.TEXT, plan.parameters().get(0).type());
        assertEquals(QueryContracts.ParameterType.DECIMAL, plan.parameters().get(1).type());
        assertEquals(QueryContracts.ParameterType.DATE, plan.parameters().get(2).type());
    }

    @Test void parameterizedPlanRetainsSourceAndDialectMetadata() {
        var plan = compiler.compileParameterized(new Query("orders", "revenue", List.of("month"), List.of(), 10));
        assertEquals("toba", plan.sourceId());
        assertEquals("ansi", plan.dialectId());
        assertTrue(plan.sqlTemplate().contains("DATE_TRUNC('MONTH', \"order_month\")"));
        assertTrue(plan.sqlTemplate().contains("GROUP BY DATE_TRUNC"));
        assertTrue(plan.parameters().isEmpty());
    }

    @Test void rejectsMismatchedParameterTypesAndInvalidValues() {
        assertThrows(IllegalArgumentException.class, () ->
            new QueryContracts.QueryParameter("p1", QueryContracts.ParameterType.DATE, "2026-01-01"));
        assertThrows(IllegalArgumentException.class, () ->
            compiler.compileParameterized(new Query("orders", "revenue", List.of(),
                List.of(new Filter("region", "eq", "bad\nvalue")), 10)));
        assertThrows(IllegalArgumentException.class, () ->
            compiler.compileParameterized(new Query("orders", "revenue", List.of(),
                List.of(new Filter("units", "eq", "not-a-number")), 10)));
    }
}