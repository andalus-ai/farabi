package tech.kayys.andalus.bi.artifact;

import org.junit.jupiter.api.Test;
import tech.kayys.andalus.bi.semantic.Aggregation;
import tech.kayys.andalus.bi.semantic.SemanticMetric;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DashboardArtifactTest {
    private static DashboardArtifact.Provenance provenance() {
        return new DashboardArtifact.Provenance(
                DashboardArtifact.AuthoringMode.AGENT_PROMOTED, "user-1", "request-1", "semantic-v1");
    }

    private static WidgetSpec widget(String id, String metricId) {
        return new WidgetSpec(id, "Revenue", "number", metricId, null, null);
    }

    @Test
    void createsAndVersionsDashboardWithoutChangingIdentity() {
        var dashboard = DashboardArtifact.create("workspace-1", "Sales", "", List.of(widget("revenue", "sales.total")), provenance());
        var next = dashboard.nextVersion("Sales overview", "Updated", List.of(widget("revenue", "sales.total")), provenance());

        assertEquals(dashboard.id(), next.id());
        assertEquals(1, dashboard.version());
        assertEquals(2, next.version());
        assertEquals("Sales", dashboard.title());
        assertEquals("Sales overview", next.title());
    }

    @Test
    void rejectsEmptyAndDuplicateWidgetLists() {
        assertThrows(IllegalArgumentException.class, () ->
                DashboardArtifact.create("ws", "Empty", "", List.of(), provenance()));
        assertThrows(IllegalArgumentException.class, () ->
                DashboardArtifact.create("ws", "Duplicate", "", List.of(
                        widget("same", "sales.total"), widget("same", "sales.total")), provenance()));
    }

    @Test
    void validatorReportsUnknownSemanticMetric() {
        var dashboard = DashboardArtifact.create("ws", "Sales", "", List.of(widget("revenue", "sales.unknown")), provenance());
        var metrics = List.of(new SemanticMetric("sales.total", "Total sales", "", "warehouse",
                "amount", Aggregation.SUM, "USD"));

        var result = DashboardValidator.validate(dashboard, metrics);

        assertFalse(result.valid());
        assertTrue(result.errors().getFirst().contains("sales.unknown"));
        assertThrows(IllegalArgumentException.class, () -> DashboardValidator.requireValid(dashboard, metrics));
    }

    @Test
    void acceptsWidgetThatReferencesKnownMetric() {
        var dashboard = DashboardArtifact.create("ws", "Sales", "", List.of(widget("revenue", "sales.total")), provenance());
        var metrics = List.of(new SemanticMetric("sales.total", "Total sales", "", "warehouse",
                "amount", Aggregation.SUM, "USD"));

        assertTrue(DashboardValidator.validate(dashboard, metrics).valid());
    }
}
