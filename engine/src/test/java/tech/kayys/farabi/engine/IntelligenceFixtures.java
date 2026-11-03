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
                new Dimension("quarter", "Quarter", "order_ts", DimensionType.TIME, Grain.QUARTER),
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

    /** Fake engine: returns rows shaped like real results (dimension ids + measure alias) for any structured query. */
    static final class FakeSource implements LiveDashboardRuntime.ChartDataSource {
        final List<String> executed = new ArrayList<>();
        private static final double[] MONTHLY = {100, 105, 110, 108, 190};

        private record Label(String text, double weight) {}
        private record Acc(Map<String, Object> row, double weight) {}

        @Override public List<Map<String, Object>> run(String ws, String subject, StructuredQueryCompiler.Query query, QueryContracts.Plan plan) {
            executed.add(plan.sql());
            List<Acc> acc = List.of(new Acc(new LinkedHashMap<>(), 1.0));
            for (String dim : query.dimensions()) {
                List<Acc> next = new ArrayList<>();
                for (Acc a : acc)
                    for (Label l : labels(dim)) {
                        Map<String, Object> row = new LinkedHashMap<>(a.row());
                        row.put(dim, l.text());
                        next.add(new Acc(row, a.weight() * l.weight()));
                    }
                acc = next;
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Acc a : acc) {
                Map<String, Object> row = new LinkedHashMap<>(a.row());
                Object month = row.get("month");
                double base = month == null ? 1000.0 : MONTHLY[Integer.parseInt(((String) month).substring(6)) - 1];
                row.put(query.measureId(), base * a.weight());
                rows.add(row);
            }
            return rows;
        }

        private static List<Label> labels(String dim) {
            return switch (dim) {
                case "month" -> List.of(new Label("2026-01", 1), new Label("2026-02", 1), new Label("2026-03", 1), new Label("2026-04", 1), new Label("2026-05", 1));
                case "region" -> List.of(new Label("APAC", 0.6), new Label("EMEA", 0.3), new Label("AMER", 0.1));
                case "channel" -> List.of(new Label("web", 0.7), new Label("store", 0.3));
                default -> List.of(new Label("a", 0.5), new Label("b", 0.5));
            };
        }
    }
}
