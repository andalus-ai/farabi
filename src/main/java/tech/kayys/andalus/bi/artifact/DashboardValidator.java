package tech.kayys.andalus.bi.artifact;

import tech.kayys.andalus.bi.semantic.SemanticMetric;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Deterministic validation boundary for both manual and model-generated artifacts. */
public final class DashboardValidator {
    private DashboardValidator() {}

    public record ValidationResult(List<String> errors) {
        public ValidationResult { errors = List.copyOf(errors); }
        public boolean valid() { return errors.isEmpty(); }
    }

    public static ValidationResult validate(DashboardArtifact dashboard, Collection<SemanticMetric> metrics) {
        List<String> errors = new ArrayList<>();
        if (dashboard == null) {
            return new ValidationResult(List.of("dashboard must not be null"));
        }
        Set<String> metricIds = new HashSet<>();
        if (metrics != null) metrics.forEach(metric -> {
            if (metric != null) metricIds.add(metric.id());
        });
        for (WidgetSpec widget : dashboard.widgets()) {
            if (!metricIds.contains(widget.metricId())) {
                errors.add("widget '" + widget.id() + "' references unknown metric '" + widget.metricId() + "'");
            }
        }
        return new ValidationResult(errors);
    }

    public static DashboardArtifact requireValid(DashboardArtifact dashboard, Collection<SemanticMetric> metrics) {
        ValidationResult result = validate(dashboard, metrics);
        if (!result.valid()) throw new IllegalArgumentException(String.join("; ", result.errors()));
        return dashboard;
    }
}
