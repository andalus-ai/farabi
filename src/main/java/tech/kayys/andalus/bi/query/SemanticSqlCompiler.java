package tech.kayys.andalus.bi.query;

import tech.kayys.andalus.bi.semantic.SemanticMetric;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * PostgreSQL-compatible compiler for a constrained semantic query subset.
 * Physical names are checked and quoted; filter values remain bind parameters.
 * Production Toba/Dremio support requires a separate dialect adapter.
 */
public final class SemanticSqlCompiler {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern TABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*){0,2}");

    public QueryPlan compile(SemanticQuery query, SemanticCatalog catalog) {
        var source = catalog.requireSource(query.sourceId());
        if (!TABLE.matcher(source.tableName()).matches()) {
            throw new IllegalArgumentException("invalid physical table identifier");
        }
        List<String> select = new ArrayList<>();
        List<String> groupBy = new ArrayList<>();
        Set<String> aliases = new HashSet<>();
        for (String dimensionId : query.dimensions()) {
            String column = source.dimensions().get(dimensionId);
            if (column == null) throw new IllegalArgumentException("unknown dimension: " + dimensionId);
            if (!aliases.add(dimensionId)) throw new IllegalArgumentException("duplicate alias: " + dimensionId);
            String quoted = column(column);
            select.add(quoted + " AS " + alias(dimensionId));
            groupBy.add(quoted);
        }
        for (String metricId : query.metricIds()) {
            SemanticMetric metric = source.metrics().get(metricId);
            if (metric == null || !metric.sourceId().equals(query.sourceId())) {
                throw new IllegalArgumentException("unknown metric: " + metricId);
            }
            if (!aliases.add(metricId)) throw new IllegalArgumentException("duplicate alias: " + metricId);
            String expression = metric.aggregation().name().equals("COUNT_DISTINCT")
                    ? "COUNT(DISTINCT " + column(metric.field()) + ")"
                    : metric.aggregation().name() + "(" + column(metric.field()) + ")";
            select.add(expression + " AS " + alias(metricId));
        }
        List<Object> parameters = new ArrayList<>();
        List<String> predicates = new ArrayList<>();
        for (var filter : query.filters()) {
            String column = source.dimensions().get(filter.fieldId());
            if (column == null) throw new IllegalArgumentException("filter must reference a governed dimension: " + filter.fieldId());
            String op = switch (filter.operator()) {
                case EQ -> "=";
                case NE -> "<>";
                case GT -> ">";
                case GTE -> ">=";
                case LT -> "<";
                case LTE -> "<=";
            };
            predicates.add(column(column) + " " + op + " ?");
            parameters.add(filter.value());
        }
        String sql = "SELECT " + String.join(", ", select)
                + " FROM " + table(source.tableName())
                + (predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates))
                + (groupBy.isEmpty() ? "" : " GROUP BY " + String.join(", ", groupBy))
                + " LIMIT ?";
        parameters.add(query.limit());
        return new QueryPlan(query.sourceId(), sql, parameters, query.limit());
    }

    private static String column(String name) {
        if (name == null || !IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException("invalid physical column identifier");
        }
        return "\"" + name + "\"";
    }

    private static String table(String name) {
        return java.util.Arrays.stream(name.split("\\.")).map(SemanticSqlCompiler::column)
                .collect(java.util.stream.Collectors.joining("."));
    }

    private static String alias(String id) {
        if (id == null || !id.matches("[a-zA-Z][a-zA-Z0-9_.-]{0,127}")) {
            throw new IllegalArgumentException("invalid semantic alias");
        }
        return "\"" + id + "\"";
    }
}
