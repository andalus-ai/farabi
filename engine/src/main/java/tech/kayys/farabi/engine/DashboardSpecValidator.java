package tech.kayys.farabi.engine;

import java.util.*;
import tech.kayys.farabi.engine.SemanticModel.*;
import tech.kayys.farabi.engine.VisualizationContracts.*;

/**
 * The single trust boundary for dashboard specifications, whether proposed by an agent or saved
 * from the conventional builder. Checks every chart against the catalog and the caller's grants,
 * and reports all problems at once so an agent or user can correct them in one round.
 */
public final class DashboardSpecValidator {
    public static final int MAX_CHARTS = 12;
    public static final int MAX_TITLE = 120;

    public record Issue(String chartId, String code, String message) {}

    public record Result(List<Issue> issues) {
        public Result { issues = List.copyOf(issues); }
        public boolean valid() { return issues.isEmpty(); }
        public void requireValid() { if (!valid()) throw new InvalidSpecException(issues); }
    }

    public static final class InvalidSpecException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final transient List<Issue> issues;
        public InvalidSpecException(List<Issue> issues) {
            super("invalid dashboard specification: " + issues.stream().map(i -> i.code() + (i.chartId() == null ? "" : "@" + i.chartId())).toList());
            this.issues = List.copyOf(issues);
        }
        public List<Issue> issues() { return issues; }
    }

    private final SemanticModel model;
    private final DatasetPolicy policy;
    private final StructuredQueryCompiler compiler;

    public DashboardSpecValidator(SemanticModel model, DatasetPolicy policy) {
        this.model = Objects.requireNonNull(model);
        this.policy = Objects.requireNonNull(policy);
        this.compiler = new StructuredQueryCompiler(model);
    }

    public Result validate(String workspaceId, String subjectId, DashboardSpec spec) {
        QueryContracts.required(workspaceId, "workspaceId");
        QueryContracts.required(subjectId, "subjectId");
        Objects.requireNonNull(spec);
        List<Issue> issues = new ArrayList<>();
        if (spec.charts().size() > MAX_CHARTS) issues.add(new Issue(null, "TOO_MANY_CHARTS", "at most " + MAX_CHARTS + " charts"));
        if (spec.title().length() > MAX_TITLE) issues.add(new Issue(null, "TITLE_TOO_LONG", "title exceeds " + MAX_TITLE + " characters"));
        for (ChartSpec chart : spec.charts()) validateChart(workspaceId, subjectId, chart, issues);
        return new Result(issues);
    }

    private void validateChart(String ws, String subject, ChartSpec chart, List<Issue> issues) {
        String id = chart.id();
        Binding b = chart.binding();
        if (chart.title().length() > MAX_TITLE) issues.add(new Issue(id, "TITLE_TOO_LONG", "title exceeds " + MAX_TITLE + " characters"));

        Optional<Dataset> found = model.authorized(ws, subject, b.datasetId(), policy);
        if (found.isEmpty()) {   // identical answer for "missing" and "forbidden"
            issues.add(new Issue(id, "DATASET_UNAVAILABLE", "dataset is unknown or not permitted"));
            return;
        }
        Dataset ds = found.get();
        int before = issues.size();

        if (ds.measure(b.valueField()).isEmpty()) issues.add(new Issue(id, "UNKNOWN_MEASURE", "value field is not a measure of the dataset"));
        Dimension category = null, series = null;
        if (b.categoryField() != null) {
            category = ds.dimension(b.categoryField()).orElse(null);
            if (category == null) issues.add(new Issue(id, "UNKNOWN_DIMENSION", "category field is not a dimension of the dataset"));
        }
        if (b.seriesField() != null) {
            series = ds.dimension(b.seriesField()).orElse(null);
            if (series == null) issues.add(new Issue(id, "UNKNOWN_DIMENSION", "series field is not a dimension of the dataset"));
            if (b.categoryField() == null) issues.add(new Issue(id, "SERIES_WITHOUT_CATEGORY", "series requires a category"));
            if (b.seriesField().equals(b.categoryField())) issues.add(new Issue(id, "DUPLICATE_DIMENSION", "series must differ from category"));
        }
        switch (chart.kind()) {
            case KPI -> { if (b.categoryField() != null) issues.add(new Issue(id, "KIND_MISMATCH", "KPI takes no category")); }
            case PIE -> {
                if (b.categoryField() == null || b.seriesField() != null) issues.add(new Issue(id, "KIND_MISMATCH", "pie needs exactly one category"));
            }
            case LINE, AREA -> {
                if (category != null && category.type() != DimensionType.TIME) issues.add(new Issue(id, "KIND_MISMATCH", "trend charts need a time dimension"));
                if (b.categoryField() == null) issues.add(new Issue(id, "KIND_MISMATCH", "trend charts need a category"));
            }
            case BAR, SCATTER -> { if (b.categoryField() == null) issues.add(new Issue(id, "KIND_MISMATCH", chart.kind() + " needs a category")); }
            case TABLE -> { }
        }
        if (issues.size() == before) {   // structure is sound; make sure the query actually compiles
            try {
                compiler.compile(StructuredQueryCompiler.Query.fromChart(chart, StructuredQueryCompiler.DEFAULT_LIMIT));
            } catch (IllegalArgumentException e) {
                issues.add(new Issue(id, "INVALID_QUERY", e.getMessage()));
            }
        }
    }
}
