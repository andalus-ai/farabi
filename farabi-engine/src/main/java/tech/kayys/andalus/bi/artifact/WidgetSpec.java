package tech.kayys.andalus.bi.artifact;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Portable widget specification; renderer-specific objects do not belong here. */
public record WidgetSpec(
        String id,
        String title,
        String kind,
        String metricId,
        Map<String, String> dimensions,
        Map<String, Object> options) {

    private static final Pattern ID_PATTERN = Pattern.compile("[a-zA-Z][a-zA-Z0-9_.-]{0,127}");
    private static final java.util.Set<String> ALLOWED_KINDS =
            java.util.Set.of("number", "line", "bar", "area", "pie", "table");

    public WidgetSpec {
        id = required("id", id);
        title = required("title", title);
        kind = required("kind", kind).toLowerCase(java.util.Locale.ROOT);
        metricId = required("metricId", metricId);
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("widget id contains unsupported characters");
        }
        if (!ALLOWED_KINDS.contains(kind)) {
            throw new IllegalArgumentException("unsupported widget kind: " + kind);
        }
        dimensions = dimensions == null ? Map.of() : Map.copyOf(dimensions);
        options = options == null ? Map.of() : Map.copyOf(options);
        dimensions.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null || value.isBlank()) {
                throw new IllegalArgumentException("dimension names and values must not be blank");
            }
        });
        Objects.requireNonNull(options);
    }

    private static String required(String name, String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
