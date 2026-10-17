package tech.kayys.andalus.bi.semantic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SemanticMetricTest {
    @Test
    void normalizesOptionalFieldsAndWhitespace() {
        var metric = new SemanticMetric("sales.total", " Total Sales ", null,
                "warehouse", "amount", Aggregation.SUM, null);

        assertEquals("Total Sales", metric.label());
        assertEquals("", metric.description());
        assertEquals("number", metric.unit());
    }

    @Test
    void rejectsBlankRequiredFields() {
        assertThrows(IllegalArgumentException.class, () ->
                new SemanticMetric("sales.total", "Sales", "", " ", "amount", Aggregation.SUM, "USD"));
    }

    @Test
    void rejectsInvalidStableId() {
        assertThrows(IllegalArgumentException.class, () ->
                new SemanticMetric("Sales Total", "Sales", "", "warehouse", "amount", Aggregation.SUM, "USD"));
    }
}
