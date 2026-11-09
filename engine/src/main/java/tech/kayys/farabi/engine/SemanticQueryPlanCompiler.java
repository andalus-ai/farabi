package tech.kayys.farabi.engine;

import java.util.*;
import java.util.stream.Collectors;
import tech.kayys.farabi.engine.SemanticQueryContracts.*;

/**
 * Compiles validated logical queries to parameterized provider plans.
 * Provider identifiers are obtained only from a workspace/subject-scoped metadata resolver;
 * request literals are always bound parameters and never interpolated into SQL.
 */
public final class SemanticQueryPlanCompiler {
    public record Field(String logicalName, String physicalName, FieldType type,
                        boolean groupable, boolean aggregatable) {
        public Field {
            QueryContracts.required(logicalName, "logicalName");
            QueryContracts.required(physicalName, "physicalName");
            Objects.requireNonNull(type, "type");
        }
    }

    public record Dataset(String sourceId, String dialectId, String qualifiedRelation,
                          Map<String, Field> fields) {
        public Dataset {
            QueryContracts.required(sourceId, "sourceId");
            QueryContracts.required(dialectId, "dialectId");
            QueryContracts.required(qualifiedRelation, "qualifiedRelation");
            fields = Map.copyOf(Objects.requireNonNull(fields, "fields"));
            fields.forEach((key, field) -> {
                if (!key.equals(field.logicalName())) throw new IllegalArgumentException("field map key mismatch");
            });
        }
    }

    /** Implementations must enforce workspace and subject scope while resolving metadata. */
    @FunctionalInterface
    public interface MetadataResolver {
        Dataset resolve(String workspaceId, String subjectId, String datasetId);
    }

    public interface Dialect {
        String id();
        String quoteIdentifier(String identifier);
        String renderLimit(int limit);
    }

    private final MetadataResolver resolver;
    private final Dialect dialect;

    public SemanticQueryPlanCompiler(MetadataResolver resolver, Dialect dialect) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
    }

    public QueryContracts.ParameterizedPlan compile(String workspaceId, String subjectId, Query query) {
        QueryContracts.required(workspaceId, "workspaceId");
        QueryContracts.required(subjectId, "subjectId");
        Objects.requireNonNull(query, "query");
        Dataset dataset = Objects.requireNonNull(
                resolver.resolve(workspaceId, subjectId, query.datasetId()), "dataset metadata not found");
        if (!dataset.dialectId().equals(dialect.id()))
            throw new IllegalArgumentException("dataset dialect does not match compiler dialect");

        List<Field> dimensions = query.dimensions().stream()
                .map(ref -> requireField(dataset, ref, true)).toList();
        List<ResolvedMeasure> measures = query.measures().stream()
                .map(measure -> new ResolvedMeasure(measure, requireField(dataset, measure.field(), false))).toList();
        List<ResolvedPredicate> predicates = query.predicates().stream()
                .map(predicate -> new ResolvedPredicate(predicate, requireField(dataset, predicate.field(), false))).toList();

        StringBuilder sql = new StringBuilder("SELECT ");
        List<String> selections = new ArrayList<>();
        for (Field dimension : dimensions) {
            selections.add(dialect.quoteIdentifier(dimension.physicalName()) + " AS "
                    + dialect.quoteIdentifier(dimension.logicalName()));
        }
        for (ResolvedMeasure measure : measures) {
            selections.add(measure.request().aggregation().name() + "("
                    + dialect.quoteIdentifier(measure.field().physicalName()) + ") AS "
                    + dialect.quoteIdentifier(measure.request().alias()));
        }
        sql.append(String.join(", ", selections));
        sql.append(" FROM ").append(quoteQualified(dataset.qualifiedRelation()));

        List<QueryContracts.QueryParameter> parameters = new ArrayList<>();
        if (!predicates.isEmpty()) {
            List<String> clauses = new ArrayList<>();
            for (ResolvedPredicate resolved : predicates) {
                Predicate predicate = resolved.request();
                clauses.add(dialect.quoteIdentifier(resolved.field().physicalName()) + " "
                        + operator(predicate.operator()) + " ?");
                parameters.add(parameter(predicate, parameters.size() + 1));
            }
            sql.append(" WHERE ").append(String.join(" AND ", clauses));
        }
        if (!dimensions.isEmpty()) {
            String grouping = dimensions.stream().map(f -> dialect.quoteIdentifier(f.physicalName()))
                    .collect(Collectors.joining(", "));
            sql.append(" GROUP BY ").append(grouping);
        }
        sql.append(" ").append(dialect.renderLimit(query.limit()));
        return new QueryContracts.ParameterizedPlan(dataset.sourceId(), dataset.dialectId(), sql.toString(), parameters);
    }

    private Field requireField(Dataset dataset, FieldRef ref, boolean grouping) {
        Field field = dataset.fields().get(ref.name());
        if (field == null) throw new IllegalArgumentException("unknown semantic field: " + ref.name());
        if (grouping && !field.groupable()) throw new IllegalArgumentException("field is not groupable: " + ref.name());
        return field;
    }

    private String quoteQualified(String relation) {
        String[] parts = relation.split("\\.", -1);
        if (parts.length == 0) throw new IllegalArgumentException("invalid qualified relation");
        return Arrays.stream(parts).map(part -> {
            QueryContracts.required(part, "relation identifier");
            return dialect.quoteIdentifier(part);
        }).collect(Collectors.joining("."));
    }

    private static String operator(Operator operator) {
        return switch (operator) {
            case EQ -> "="; case NE -> "<>"; case GT -> ">"; case GTE -> ">=";
            case LT -> "<"; case LTE -> "<=";
        };
    }

    private static QueryContracts.QueryParameter parameter(Predicate predicate, int index) {
        QueryContracts.ParameterType type = switch (predicate.valueType()) {
            case TEXT -> QueryContracts.ParameterType.TEXT;
            case DECIMAL -> QueryContracts.ParameterType.DECIMAL;
            case DATE -> QueryContracts.ParameterType.DATE;
            case BOOLEAN -> QueryContracts.ParameterType.BOOLEAN;
        };
        return new QueryContracts.QueryParameter("p" + index, type, predicate.value());
    }

    private record ResolvedMeasure(Measure request, Field field) {}
    private record ResolvedPredicate(Predicate request, Field field) {}
}
