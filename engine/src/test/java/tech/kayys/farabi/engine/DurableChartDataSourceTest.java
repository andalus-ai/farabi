package tech.kayys.farabi.engine;

import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.DashboardManifests.Manifest;
import tech.kayys.farabi.engine.InsightEngine.Type;
import tech.kayys.farabi.engine.QueryContracts.*;
import tech.kayys.farabi.engine.QueryLifecycle.*;
import tech.kayys.farabi.engine.StructuredQueryCompiler.Query;
import tech.kayys.farabi.engine.VisualizationContracts.*;
import static org.junit.jupiter.api.Assertions.*;

/** Chart queries must travel through the same durable, grant-checked path as every other query. */
class DurableChartDataSourceTest {
    private final IntelligentDashboardFlowTest.MutableClock clock = new IntelligentDashboardFlowTest.MutableClock();
    private final DurableQueryServiceTest.Store store = new DurableQueryServiceTest.Store();
    private final List<Plan> submitted = new ArrayList<>();
    private final List<String> cancelled = new ArrayList<>();
    private final Set<PolicyAuthorizer.Grant> grants = IntelligenceFixtures.grants("ws", "alice", "orders");
    private final StructuredQueryCompiler compiler = new StructuredQueryCompiler(IntelligenceFixtures.model());
    private List<Map<String, Object>> data = new ArrayList<>();
    private int pageSize = 1000;

    private final Provider provider = new Provider() {
        public RemoteState status(String id) { return new RemoteState(Status.RUNNING, null); }
        public ResultPage results(String id, int offset, int limit) {
            int to = Math.min(Math.min(data.size(), offset + Math.min(limit, pageSize)), data.size());
            return new ResultPage(List.of(), data.subList(offset, to), offset, to < data.size());
        }
        public void cancel(String id) { cancelled.add(id); }
    };

    /** Stands in for the polling worker: moves the local execution record forward on each wait. */
    private DurableChartDataSource.Progress worker(Status terminal, int ticks) {
        Map<UUID, Integer> seen = new HashMap<>();   // per execution, like a real poller
        return (id, ws) -> {
            clock.now = clock.now.plusSeconds(1);
            var e = store.items.get(id);
            int n = seen.merge(id, 1, Integer::sum);
            if (n == 1 && e.status() == Status.SUBMITTED) store.transition(id, ws, Status.SUBMITTED, Status.RUNNING, e.providerJobId());
            if (n >= ticks && store.items.get(id).status() == Status.RUNNING)
                store.transition(id, ws, Status.RUNNING, terminal, e.providerJobId());
        };
    }

    private DurableChartDataSource source(DurableChartDataSource.Progress progress, int maxRows) {
        return new DurableChartDataSource(new PolicyAuthorizer(grants), plan -> { submitted.add(plan); return new Submission("job-" + submitted.size()); },
            store, new QueryLifecycleService(store, provider), clock, Duration.ofSeconds(3), progress, maxRows);
    }

    private static final Query REGION = new Query("orders", "revenue", List.of("region"), List.of(), 500);

    @Test void submitsTheCompiledPlanAndPagesThroughAllRows() {
        for (int i = 0; i < 5; i++) data.add(Map.of("region", "R" + i, "revenue", 10.0 * i));
        pageSize = 2;
        var plan = compiler.compile(REGION);
        var rows = source(worker(Status.SUCCEEDED, 2), 1000).run("ws", "alice", REGION, plan);
        assertEquals(5, rows.size());
        assertEquals(List.of(plan), submitted, "exactly the compiler's plan reaches the executor");
        assertEquals(Status.SUCCEEDED, store.items.values().iterator().next().status(), "execution was persisted and tracked");
    }

    @Test void failedAndCancelledQueriesRaise() {
        var plan = compiler.compile(REGION);
        assertEquals("chart query failed", assertThrows(IllegalStateException.class,
            () -> source(worker(Status.FAILED, 2), 1000).run("ws", "alice", REGION, plan)).getMessage());
        assertEquals("chart query cancelled", assertThrows(IllegalStateException.class,
            () -> source(worker(Status.CANCELLED, 2), 1000).run("ws", "alice", REGION, plan)).getMessage());
    }

    @Test void timeoutCancelsTheRemoteJobAndFailsTheChart() {
        var plan = compiler.compile(REGION);
        assertThrows(DurableChartDataSource.QueryTimeoutException.class,
            () -> source(worker(Status.SUCCEEDED, 1000), 1000).run("ws", "alice", REGION, plan));
        assertEquals(List.of("job-1"), cancelled);
    }

    @Test void deniedGrantNeverReachesTheExecutor() {
        grants.clear();
        var plan = compiler.compile(REGION);
        assertThrows(SecurityException.class, () -> source(worker(Status.SUCCEEDED, 1), 1000).run("ws", "alice", REGION, plan));
        assertTrue(submitted.isEmpty());
        assertTrue(store.items.isEmpty(), "nothing is persisted for a denied query");
    }

    @Test void resultsBeyondTheRowCapAreRefusedNotTruncated() {
        for (int i = 0; i < 5; i++) data.add(Map.of("region", "R" + i, "revenue", 1.0));
        pageSize = 2;
        var plan = compiler.compile(REGION);
        var ex = assertThrows(IllegalStateException.class, () -> source(worker(Status.SUCCEEDED, 2), 3).run("ws", "alice", REGION, plan));
        assertTrue(ex.getMessage().contains("row cap"), ex.getMessage());
    }

    @Test void aDashboardPreviewRunsEndToEndThroughTheDurableStack() {
        var model = IntelligenceFixtures.model();
        var policy = IntelligenceFixtures.policy(grants);
        var kpi = new ChartSpec("kpi", Kind.KPI, "Total revenue", new Binding("orders", null, "revenue", null), List.of());
        var bar = new ChartSpec("bar", Kind.BAR, "Revenue by region", new Binding("orders", "region", "revenue", null), List.of());
        var spec = new DashboardSpec("Revenue", List.of(kpi, bar), DashboardLayouts.flow(List.of(kpi, bar)));
        var answers = new ArrayDeque<List<Map<String, Object>>>(List.of(
            List.of(Map.<String, Object>of("revenue", 1000.0)),
            List.of(Map.<String, Object>of("region", "APAC", "revenue", 600.0), Map.<String, Object>of("region", "EMEA", "revenue", 300.0),
                    Map.<String, Object>of("region", "AMER", "revenue", 100.0))));
        Provider sequenced = new Provider() {
            public RemoteState status(String id) { return new RemoteState(Status.RUNNING, null); }
            public ResultPage results(String id, int offset, int limit) { return new ResultPage(List.of(), id.equals("job-1") ? answers.peekFirst() : answers.peekLast(), 0, false); }
            public void cancel(String id) { }
        };
        var durable = new DurableChartDataSource(new PolicyAuthorizer(grants), plan -> { submitted.add(plan); return new Submission("job-" + submitted.size()); },
            store, new QueryLifecycleService(store, sequenced), clock, Duration.ofSeconds(3), worker(Status.SUCCEEDED, 2), 1000);
        var noManifests = new DashboardManifests.Repository() {
            public void save(Manifest m) { }
            public Optional<Manifest> find(UUID id, String ws) { return Optional.empty(); }
        };
        var runtime = new LiveDashboardRuntime(noManifests, new DashboardSpecValidator(model, policy), compiler, durable, new InsightEngine(), Audit.NONE);

        var live = runtime.preview(UUID.randomUUID(), "ws", "alice", spec);
        assertEquals(2, live.rendered().data().size());
        assertEquals(1000.0, live.rendered().data().get(0).rows().get(0).get("revenue"));
        assertTrue(live.insights().stream().anyMatch(i -> i.type() == Type.TOP_CONTRIBUTOR && i.headline().startsWith("APAC contributes 60.0%")));
        assertEquals(2, submitted.size());
        submitted.forEach(p -> assertTrue(p.sql().startsWith("SELECT ") && p.sql().contains("FROM \"sales\".\"orders\"")));
    }
}
