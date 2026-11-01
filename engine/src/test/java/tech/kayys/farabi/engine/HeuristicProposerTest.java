package tech.kayys.farabi.engine;

import java.util.*;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.DashboardPlanner.Request;
import tech.kayys.farabi.engine.VisualizationContracts.*;
import static org.junit.jupiter.api.Assertions.*;

class HeuristicProposerTest {
    private final HeuristicProposer proposer = new HeuristicProposer();
    private final Set<PolicyAuthorizer.Grant> grants = IntelligenceFixtures.grants("ws", "alice", "orders", "payroll");
    private final SemanticModel model = IntelligenceFixtures.model();
    private final DashboardSpecValidator validator = new DashboardSpecValidator(model, IntelligenceFixtures.policy(grants));

    private DashboardPlanner.Proposal ask(String prompt) {
        return proposer.propose(new Request("ws", "alice", prompt, model.viewFor("ws", "alice", IntelligenceFixtures.policy(grants))));
    }
    private static Map<String, ChartSpec> byId(DashboardSpec s) {
        Map<String, ChartSpec> m = new LinkedHashMap<>(); s.charts().forEach(c -> m.put(c.id(), c)); return m;
    }

    @Test void trendByRegionWithFilter() {
        var p = ask("Show revenue trend by region where channel is web");
        var charts = byId(p.spec());
        assertEquals(Kind.KPI, charts.get("kpi_revenue").kind());
        var line = charts.get("trend_revenue_month");
        assertEquals(Kind.LINE, line.kind());
        assertEquals("region", line.binding().seriesField());
        assertEquals(List.of(new Filter("channel", "eq", "web")), line.filters());
        assertTrue(p.assumptions().stream().anyMatch(a -> a.contains("Filtering Channel = web")));
        assertTrue(validator.validate("ws", "alice", p.spec()).valid());
        assertEquals("heuristic-v1", p.proposer());
    }

    @Test void shareRequestsProducePieAndMultipleMeasuresProduceKpis() {
        var p = ask("revenue and order count share by region");
        var charts = byId(p.spec());
        assertEquals(Kind.PIE, charts.get("pie_revenue_region").kind());
        assertTrue(charts.containsKey("kpi_order_count"));
        assertTrue(validator.validate("ws", "alice", p.spec()).valid());
    }

    @Test void vagueRequestsStateTheirAssumptions() {
        var p = ask("payroll overview");
        assertTrue(p.assumptions().stream().anyMatch(a -> a.startsWith("No measure named in the request; using Salary")), p.assumptions().toString());
        assertTrue(p.assumptions().stream().anyMatch(a -> a.startsWith("No dimension named in the request; breaking down by Department")), p.assumptions().toString());
        assertTrue(validator.validate("ws", "alice", p.spec()).valid());
    }

    @Test void firstMentionedMeasureIsPrimaryEvenWhenAnotherLabelHasMoreWords() {
        var charts = byId(ask("order count and revenue by region").spec());
        assertTrue(charts.containsKey("bar_order_count_region"), charts.keySet().toString());
        charts = byId(ask("revenue and order count by region").spec());
        assertTrue(charts.containsKey("bar_revenue_region"), charts.keySet().toString());
    }

    @Test void onlySeesAuthorizedDatasets() {
        grants.remove(new PolicyAuthorizer.Grant("ws", "alice", "toba", "payroll"));
        assertThrows(IllegalArgumentException.class, () -> ask("salary by department"));
        grants.add(new PolicyAuthorizer.Grant("ws", "alice", "toba", "payroll"));
        assertEquals("payroll", ask("salary by department").spec().charts().get(0).binding().datasetId());
    }

    @Test void namedGrainSelectsTheMatchingTimeDimension() {
        var p = ask("revenue by quarter");
        var line = byId(p.spec()).get("trend_revenue_quarter");
        assertNotNull(line, byId(p.spec()).keySet().toString());
        assertEquals("quarter", line.binding().categoryField());
        assertTrue(validator.validate("ws", "alice", p.spec()).valid());
    }

    @Test void unmatchedRequestsAreRefusedRatherThanGuessed() {
        assertThrows(IllegalArgumentException.class, () -> ask("tell me a joke"));
    }

    @Test void caseOfFilterValuesIsPreserved() {
        var p = ask("revenue for region = APAC");
        assertEquals("APAC", p.spec().charts().get(0).filters().get(0).value());
    }
}
