package tech.kayys.farabi.engine;

import java.util.*;
import tech.kayys.farabi.engine.DashboardManifests.Manifest;
import tech.kayys.farabi.engine.DashboardRuntime.ChartData;
import tech.kayys.farabi.engine.InsightEngine.Insight;
import tech.kayys.farabi.engine.QueryContracts.Plan;
import tech.kayys.farabi.engine.StructuredQueryCompiler.Query;
import tech.kayys.farabi.engine.VisualizationContracts.*;

/**
 * Executes a dashboard specification, dynamic or static, through one governed path:
 * validate (including dataset grants) -> compile every chart -> only then fetch data -> compute insights.
 * Static dashboards are re-validated on every load, so a revoked grant takes effect immediately.
 */
public final class LiveDashboardRuntime {
    public enum Mode { DYNAMIC_PREVIEW, STATIC_PUBLISHED }

    /** Runs a compiled plan on the caller's behalf and returns rows keyed by dimension and measure ids. */
    @FunctionalInterface
    public interface ChartDataSource {
        /** The structured query is passed alongside its compiled plan so sources can authorize and audit by meaning. */
        List<Map<String, Object>> run(String workspaceId, String subjectId, Query query, Plan plan);
    }

    public record LiveDashboard(Mode mode, DashboardRuntime.Rendered rendered, List<Insight> insights, String specDigest) {
        public LiveDashboard { insights = List.copyOf(insights); }
        /** Versioned JSON-friendly document for Batik/Flutter clients. */
        public DashboardTransport.Document transport() { return DashboardTransport.ready(rendered, insights); }
    }

    private final DashboardManifests.Repository manifests;
    private final DashboardSpecValidator validator;
    private final StructuredQueryCompiler compiler;
    private final ChartDataSource source;
    private final InsightEngine insights;
    private final Audit.Sink audit;

    public LiveDashboardRuntime(DashboardManifests.Repository manifests, DashboardSpecValidator validator,
                                StructuredQueryCompiler compiler, ChartDataSource source, InsightEngine insights, Audit.Sink audit) {
        this.manifests = Objects.requireNonNull(manifests);
        this.validator = Objects.requireNonNull(validator);
        this.compiler = Objects.requireNonNull(compiler);
        this.source = Objects.requireNonNull(source);
        this.insights = Objects.requireNonNull(insights);
        this.audit = Objects.requireNonNull(audit);
    }

    public LiveDashboard loadPublished(UUID manifestId, String workspaceId, String subjectId) {
        QueryContracts.required(workspaceId, "workspaceId");
        Manifest m = manifests.find(Objects.requireNonNull(manifestId), workspaceId)
            .filter(x -> x.workspaceId().equals(workspaceId))
            .orElseThrow(() -> new IllegalArgumentException("manifest not found"));
        LiveDashboard live = execute(Mode.STATIC_PUBLISHED, m.id(), m.revision(), workspaceId, subjectId, m.spec());
        audit.record(new Audit.Event("dashboard.loaded", workspaceId, subjectId, "manifest=" + m.id() + " revision=" + m.revision()));
        return live;
    }

    /** Preview of an unpublished draft spec. The caller (draft service) has already checked draft ownership. */
    public LiveDashboard preview(UUID draftId, String workspaceId, String subjectId, DashboardSpec spec) {
        LiveDashboard live = execute(Mode.DYNAMIC_PREVIEW, draftId, 0, workspaceId, subjectId, spec);
        audit.record(new Audit.Event("draft.previewed", workspaceId, subjectId, "draft=" + draftId + " digest=" + live.specDigest()));
        return live;
    }

    /** A source that returns differently named columns (e.g. case-folded aliases) must fail loudly, not render blanks. */
    private static void requireShape(ChartSpec chart, Query q, List<Map<String, Object>> rows) {
        Set<String> expected = new LinkedHashSet<>(q.dimensions());
        expected.add(q.measureId());
        for (Map<String, Object> row : rows)
            if (!row.keySet().containsAll(expected))
                throw new IllegalStateException("result shape mismatch for chart " + chart.id() + ": expected columns " + expected);
    }

    private LiveDashboard execute(Mode mode, UUID id, int revision, String ws, String subject, DashboardSpec spec) {
        validator.validate(ws, subject, spec).requireValid();          // authorization before any data access
        List<Query> queries = new ArrayList<>();                       // compile everything first: no partial disclosure
        List<Plan> plans = new ArrayList<>();
        for (ChartSpec c : spec.charts()) {
            Query q = Query.fromChart(c, StructuredQueryCompiler.DEFAULT_LIMIT);
            queries.add(q);
            plans.add(compiler.compile(q));
        }

        var envelope = RendererEnvelope.from(spec, id, revision, ws);
        List<ChartData> data = new ArrayList<>();
        List<Insight> found = new ArrayList<>();
        for (int i = 0; i < spec.charts().size(); i++) {
            ChartSpec chart = spec.charts().get(i);
            List<Map<String, Object>> rows = List.copyOf(Objects.requireNonNull(source.run(ws, subject, queries.get(i), plans.get(i))));
            requireShape(chart, queries.get(i), rows);
            data.add(new ChartData(chart.id(), envelope.charts().get(i).renderer(), rows));
            found.addAll(insights.analyze(chart, rows));
        }
        return new LiveDashboard(mode, new DashboardRuntime.Rendered(envelope, data), found, SpecDigest.of(spec));
    }
}
