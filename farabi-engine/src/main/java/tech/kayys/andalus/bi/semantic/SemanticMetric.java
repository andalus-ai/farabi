package tech.kayys.andalus.bi.semantic;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A governed metric definition. The ID is machine-stable; the label is presentation-only.
 * SQL expressions are intentionally not accepted in this P0 contract.
 */
public record SemanticMetric(
        String id,
        String label,
        String description,
        String sourceId,
        String field,
        Aggregation aggregation,
        String unit) {

    private static final Pattern ID_PATTERN = Pattern.compile("[a-z][a-z0-9_.-]{1,127}");

    public SemanticMetric {
        id = required("id", id);
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("id must be a stable lowercase identifier (2-128 chars)");
        }
        label = required("label", label);
        description = description == null ? "" : description.trim();
        sourceId = required("sourceId", sourceId);
        field = required("field", field);
        aggregation = Objects.requireNonNull(aggregation, "aggregation");
        unit = unit == null || unit.isBlank() ? "number" : unit.trim();
    }

    private static String required(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
