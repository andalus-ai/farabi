package tech.kayys.farabi.engine;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;
import tech.kayys.farabi.engine.QueryContracts.Plan;
import tech.kayys.farabi.engine.SemanticModel.*;
import tech.kayys.farabi.engine.VisualizationContracts.*;

/**
 * Compiles a structured analytics query (one measure, up to two dimensions, typed filters)
 * into SQL. Callers never supply SQL text: identifiers come from the catalog and filter
 * values are re-parsed by declared type, so nothing user- or agent-supplied is concatenated raw.
 */
public final class StructuredQueryCompiler {
    public static final int DEFAULT_LIMIT = 500;
    public static final int MAX_LIMIT = 1000;
    public static final int MAX_FILTERS = 12;
    private static final Pattern SAFE_TEXT = Pattern.compile("[\\p{L}\\p{N} _.:/-]{1,64}");

    public record Query(String datasetId, String measureId, List<String> dimensions, List<Filter> filters, int limit) {
        public Query {
            QueryContracts.required(datasetId, "datasetId");
            QueryContracts.required(measureId, "measureId");
            dimensions = List.copyOf(Objects.requireNonNull(dimensions));
            filters = List.copyOf(Objects.requireNonNull(filters));
            if (filters.size() > MAX_FILTERS) throw new IllegalArgumentException("at most " + MAX_FILTERS + " filters");
            if (dimensions.size() > 2) throw new IllegalArgumentException("at most two dimensions");
            if (new HashSet<>(dimensions).size() != dimensions.size()) throw new IllegalArgumentException("duplicate dimension");
            if (limit < 1 || limit > MAX_LIMIT) throw new IllegalArgumentException("limit must be 1.." + MAX_LIMIT);
        }
        /** A chart specification fully determines its query, so static dashboards re-run identically. */
        public static Query fromChart(ChartSpec chart, int limit) {
            Binding b = chart.binding();
            List<String> dims = new ArrayList<>();
            if (b.categoryField() != null) dims.add(b.categoryField());
            if (b.seriesField() != null) dims.add(b.seriesField());
            return new Query(b.datasetId(), b.valueField(), dims, chart.filters(), limit);
        }
    }

    private final SemanticModel model;

    public StructuredQueryCompiler(SemanticModel model) { this.model = Objects.requireNonNull(model); }

    public Plan compile(Query q) {
        Objects.requireNonNull(q);
        Dataset ds = model.dataset(q.datasetId()).orElseThrow(() -> new IllegalArgumentException("unknown dataset"));
        Measure measure = ds.measure(q.measureId()).orElseThrow(() -> new IllegalArgumentException("unknown measure"));
        List<Dimension> dims = q.dimensions().stream()
            .map(id -> ds.dimension(id).orElseThrow(() -> new IllegalArgumentException("unknown dimension"))).toList();

        String aggregate = measure.aggregation() + "(" + quote(measure.column()) + ")";
        StringBuilder sql = new StringBuilder("SELECT ");
        for (Dimension d : dims) sql.append(expression(d)).append(" AS ").append(quote(d.id())).append(", ");
        sql.append(aggregate).append(" AS ").append(quote(measure.id()));
        sql.append(" FROM ");
        sql.append(String.join(".", Arrays.stream(ds.qualifiedTable().split("\\.")).map(StructuredQueryCompiler::quote).toList()));

        List<String> predicates = new ArrayList<>();
        for (Filter f : q.filters()) {
            Dimension d = ds.dimension(f.field()).orElseThrow(() -> new IllegalArgumentException("unknown filter field"));
            predicates.add(quote(d.column()) + " " + operator(f.operator(), d) + " " + literal(d, f.value()));
        }
        if (!predicates.isEmpty()) sql.append(" WHERE ").append(String.join(" AND ", predicates));

        if (!dims.isEmpty()) {
            sql.append(" GROUP BY ").append(String.join(", ", dims.stream().map(StructuredQueryCompiler::expression).toList()));
            List<String> order = new ArrayList<>();
            if (dims.get(0).type() != DimensionType.TIME) order.add(aggregate + " DESC");
            for (Dimension d : dims) order.add(expression(d) + " ASC");
            sql.append(" ORDER BY ").append(String.join(", ", order));
        }
        sql.append(" LIMIT ").append(q.limit());
        return new Plan(ds.sourceId(), sql.toString());
    }

    /**
     * Compiles the same governed query into a parameterized SQL template.
     * The legacy compile(Query) method remains unchanged for existing adapters.
     */
    public QueryContracts.ParameterizedPlan compileParameterized(Query q) {
        return compileParameterized(q, new AnsiSqlDialect());
    }

    public QueryContracts.ParameterizedPlan compileParameterized(Query q, SqlDialect dialect) {
        Objects.requireNonNull(q, "query");
        Objects.requireNonNull(dialect, "dialect");
        QueryContracts.required(dialect.id(), "dialect id");
        Dataset ds = model.dataset(q.datasetId()).orElseThrow(() -> new IllegalArgumentException("unknown dataset"));
        Measure measure = ds.measure(q.measureId()).orElseThrow(() -> new IllegalArgumentException("unknown measure"));
        List<Dimension> dims = q.dimensions().stream()
            .map(id -> ds.dimension(id).orElseThrow(() -> new IllegalArgumentException("unknown dimension"))).toList();

        String aggregate = measure.aggregation() + "(" + dialect.quoteIdentifier(measure.column()) + ")";
        StringBuilder sql = new StringBuilder("SELECT ");
        for (Dimension d : dims) sql.append(expression(d, dialect)).append(" AS ")
            .append(dialect.quoteIdentifier(d.id())).append(", ");
        sql.append(aggregate).append(" AS ").append(dialect.quoteIdentifier(measure.id()));
        sql.append(" FROM ");
        sql.append(String.join(".", Arrays.stream(ds.qualifiedTable().split("\\."))
            .map(dialect::quoteIdentifier).toList()));

        List<String> predicates = new ArrayList<>();
        List<QueryContracts.QueryParameter> parameters = new ArrayList<>();
        for (Filter f : q.filters()) {
            Dimension d = ds.dimension(f.field()).orElseThrow(() -> new IllegalArgumentException("unknown filter field"));
            predicates.add(dialect.quoteIdentifier(d.column()) + " " + operator(f.operator(), d) + " ?");
            parameters.add(parameter(d, f.value(), parameters.size() + 1));
        }
        if (!predicates.isEmpty()) sql.append(" WHERE ").append(String.join(" AND ", predicates));

        if (!dims.isEmpty()) {
            sql.append(" GROUP BY ").append(String.join(", ", dims.stream()
                .map(d -> expression(d, dialect)).toList()));
            List<String> order = new ArrayList<>();
            if (dims.get(0).type() != DimensionType.TIME) order.add(aggregate + " DESC");
            for (Dimension d : dims) order.add(expression(d, dialect) + " ASC");
            sql.append(" ORDER BY ").append(String.join(", ", order));
        }
        sql.append(" ").append(dialect.renderLimit(q.limit()));
        return new QueryContracts.ParameterizedPlan(ds.sourceId(), dialect.id(), sql.toString(), parameters);
    }

    private static QueryContracts.QueryParameter parameter(Dimension dimension, String value, int index) {
        if (value == null) throw new IllegalArgumentException("filter value is required");
        String name = "p" + index;
        try {
            return switch (dimension.type()) {
                case CATEGORY -> {
                    if (value.isBlank() || value.length() > 64 || value.chars().anyMatch(Character::isISOControl))
                        throw new IllegalArgumentException("unsupported filter value");
                    yield new QueryContracts.QueryParameter(name, QueryContracts.ParameterType.TEXT, value);
                }
                case NUMBER -> new QueryContracts.QueryParameter(name, QueryContracts.ParameterType.DECIMAL,
                    new BigDecimal(value));
                case TIME -> new QueryContracts.QueryParameter(name, QueryContracts.ParameterType.DATE,
                    LocalDate.parse(value));
            };
        } catch (NumberFormatException | java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("filter value does not match field type");
        }
    }

    private static String expression(Dimension dimension, SqlDialect dialect) {
        String column = dialect.quoteIdentifier(dimension.column());
        return dimension.grain() == null ? column : dialect.renderDateBucket(dimension.grain(), column);
    }

    /** Grouping expression; filters always compare the raw column, never the truncated one. */
    private static String expression(Dimension d) {
        return d.grain() == null ? quote(d.column()) : "DATE_TRUNC('" + d.grain().name() + "', " + quote(d.column()) + ")";
    }

    private static String operator(String op, Dimension d) {
        if (d.type() == DimensionType.CATEGORY && !op.equals("eq") && !op.equals("ne"))
            throw new IllegalArgumentException("category filters support eq and ne only");
        return switch (op) {
            case "eq" -> "="; case "ne" -> "<>"; case "gt" -> ">";
            case "gte" -> ">="; case "lt" -> "<"; case "lte" -> "<=";
            default -> throw new IllegalArgumentException("unsupported filter operator");
        };
    }

    private static String literal(Dimension d, String value) {
        try {
            return switch (d.type()) {
                case CATEGORY -> {
                    if (!SAFE_TEXT.matcher(value).matches()) throw new IllegalArgumentException("unsupported filter value");
                    yield "'" + value + "'";
                }
                case NUMBER -> new BigDecimal(value).toPlainString();
                case TIME -> "DATE '" + LocalDate.parse(value) + "'";
            };
        } catch (NumberFormatException | java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("filter value does not match field type");
        }
    }

    private static String quote(String identifier) {
        SemanticModel.identifier(identifier, "identifier");
        return "\"" + identifier + "\"";
    }
}
