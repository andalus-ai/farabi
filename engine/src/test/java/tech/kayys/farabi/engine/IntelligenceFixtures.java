package tech.kayys.farabi.engine;

import java.util.*;
import tech.kayys.farabi.engine.SemanticModel.*;

/** Shared catalog, policy and fake data source for the intelligent-dashboard tests. */
final class IntelligenceFixtures {
    private IntelligenceFixtures() {}

    static final Dataset ORDERS = new Dataset("orders", "Orders", "toba", "sales.orders",
        List.of(new Measure("revenue", "Revenue", "revenue", Aggregation.SUM),
                new Measure("order_count", "Order count", "order_id", Aggregation.COUNT)),
        List.of(new Dimension("region", "Region", "region", DimensionType.CATEGORY),
                new Dimension("channel", "Channel", "sales_channel", DimensionType.CATEGORY),
                new Dimension("month", "Month", "order_month", DimensionType.TIME),
                new Dimension("units", "Units", "units", DimensionType.NUMBER)));

    static final Dataset PAYROLL = new Dataset("payroll", "Payroll", "toba", "hr.payroll",
        List.of(new Measure("salary", "Salary", "salary", Aggregation.SUM)),
        List.of(new Dimension("department", "Department", "department", DimensionType.CATEGORY)));

    static SemanticModel model() { return new SemanticModel(List.of(ORDERS, PAYROLL)); }

    static Set<PolicyAuthorizer.Grant> grants(String ws, String subject, String... datasets) {
        Set<PolicyAuthorizer.Grant> g = new HashSet<>();
        for (String d : datasets) g.add(new PolicyAuthorizer.Grant(ws, subject, "toba", d));
        return g;
    }

    /** Live view over a mutable grant set so tests can revoke access. */
    static DatasetPolicy policy(Set<PolicyAuthorizer.Grant> grants) {
        return (w, s, src, d) -> grants.contains(new PolicyAuthorizer.Grant(w, s, src, d));
    }

    /** Fake engine: answers by inspecting compiled SQL and records everything it was asked to run. */
    static final class FakeSource implements LiveDashboardRuntime.ChartDataSource {
        final List<String> executed = new ArrayList<>();
        @Override public List<Map<String, Object>> run(String ws, String subject, QueryContracts.Plan plan) {
            executed.add(plan.sql());
            String sql = plan.sql();
            if (sql.contains("GROUP BY \"order_month\"")) {
                List<Map<String, Object>> rows = new ArrayList<>();
                double[] v = {100, 105, 110, 108, 190};
                for (int i = 0; i < v.length; i++) rows.add(row("month", "2026-0" + (i + 1), "revenue", v[i]));
                return rows;
            }
            if (sql.contains("GROUP BY \"region\"")) {
                return List.of(row("region", "APAC", "revenue", 600.0), row("region", "EMEA", "revenue", 300.0), row("region", "AMER", "revenue", 100.0));
            }
            return List.of(row("revenue", 1000.0));
        }
        private static Map<String, Object> row(Object... kv) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
            return m;
        }
    }
}
