package tech.kayys.farabi.engine;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import tech.kayys.farabi.engine.SemanticQueryContracts.*;

/**
 * Validates semantic requests against the caller's governed catalog before any
 * provider-specific planner or execution adapter sees them.
 */
public final class SemanticQueryValidator {
    private final Catalog catalog;

    public SemanticQueryValidator(Catalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    public Query validate(String workspaceId, String subjectId, Query query) {
        QueryContracts.required(workspaceId, "workspaceId");
        QueryContracts.required(subjectId, "subjectId");
        Objects.requireNonNull(query, "query");

        // Dataset authorization precedes field resolution to avoid catalog probing.
        catalog.authorizeDataset(workspaceId, subjectId, query.datasetId());

        Set<String> selected = new HashSet<>();
        for (FieldRef dimension : query.dimensions()) {
            CatalogField field = requireField(workspaceId, subjectId, query.datasetId(), dimension);
            if (!field.groupable()) throw new IllegalArgumentException("field is not groupable: " + dimension.name());
            selected.add(dimension.name());
        }
        for (Measure measure : query.measures()) {
            CatalogField field = requireField(workspaceId, subjectId, query.datasetId(), measure.field());
            if (measure.aggregation() != Aggregation.COUNT && !field.aggregatable())
                throw new IllegalArgumentException("field is not aggregatable: " + measure.field().name());
            if ((measure.aggregation() == Aggregation.SUM || measure.aggregation() == Aggregation.AVG)
                    && field.type() != FieldType.DECIMAL)
                throw new IllegalArgumentException("SUM/AVG require a decimal field: " + measure.field().name());
            if (!selected.add(measure.alias()))
                throw new IllegalArgumentException("measure alias conflicts with selected field: " + measure.alias());
        }
        for (Predicate predicate : query.predicates()) {
            CatalogField field = requireField(workspaceId, subjectId, query.datasetId(), predicate.field());
            if (field.type() != predicate.valueType())
                throw new IllegalArgumentException("predicate type does not match catalog field: " + predicate.field().name());
        }
        return query;
    }

    private CatalogField requireField(String workspace, String subject, String dataset, FieldRef ref) {
        CatalogField field = Objects.requireNonNull(
                catalog.requireField(workspace, subject, dataset, ref),
                "catalog returned no metadata for field " + ref.name());
        if (!field.ref().equals(ref)) throw new IllegalStateException("catalog returned mismatched field metadata");
        return field;
    }
}
