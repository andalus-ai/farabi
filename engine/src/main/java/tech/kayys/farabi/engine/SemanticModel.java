package tech.kayys.farabi.engine;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Governed semantic layer shared by conventional and agentic authoring.
 * Datasets, measures and dimensions are trusted, server-controlled metadata;
 * every SQL identifier is validated once, here, so later stages can rely on it.
 */
public final class SemanticModel {
    public enum Aggregation { SUM, AVG, MIN, MAX, COUNT }
    public enum DimensionType { CATEGORY, TIME, NUMBER }

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z_0-9]{0,63}");

    public record Measure(String id, String label, String column, Aggregation aggregation) {
        public Measure {
            identifier(id, "measure id");
            identifier(column, "measure column");
            Objects.requireNonNull(aggregation, "aggregation");
            label = label == null || label.isBlank() ? id : label;
        }
    }

    public record Dimension(String id, String label, String column, DimensionType type) {
        public Dimension {
            identifier(id, "dimension id");
            identifier(column, "dimension column");
            Objects.requireNonNull(type, "type");
            label = label == null || label.isBlank() ? id : label;
        }
    }

    public record Dataset(String id, String label, String sourceId, String qualifiedTable,
                          List<Measure> measures, List<Dimension> dimensions) {
        public Dataset {
            identifier(id, "dataset id");
            QueryContracts.required(sourceId, "sourceId");
            QueryContracts.required(qualifiedTable, "table");
            for (String part : qualifiedTable.split("\\.", -1)) identifier(part, "table");
            label = label == null || label.isBlank() ? id : label;
            measures = List.copyOf(Objects.requireNonNull(measures, "measures"));
            dimensions = List.copyOf(Objects.requireNonNull(dimensions, "dimensions"));
            if (measures.isEmpty()) throw new IllegalArgumentException("at least one measure required");
            Set<String> names = new HashSet<>();
            for (Measure m : measures) if (!names.add(m.id())) throw new IllegalArgumentException("duplicate field " + m.id());
            for (Dimension d : dimensions) if (!names.add(d.id())) throw new IllegalArgumentException("duplicate field " + d.id());
        }
        public Optional<Measure> measure(String measureId) {
            return measures.stream().filter(m -> m.id().equals(measureId)).findFirst();
        }
        public Optional<Dimension> dimension(String dimensionId) {
            return dimensions.stream().filter(d -> d.id().equals(dimensionId)).findFirst();
        }
    }

    /** Decides whether an authenticated subject may read a dataset. Implemented by the host runtime. */
    @FunctionalInterface
    public interface DatasetPolicy {
        boolean allows(String workspaceId, String subjectId, String sourceId, String datasetId);

        static DatasetPolicy fromGrants(Collection<PolicyAuthorizer.Grant> grants) {
            Set<PolicyAuthorizer.Grant> copy = Set.copyOf(grants);
            return (w, s, src, d) -> copy.contains(new PolicyAuthorizer.Grant(w, s, src, d));
        }
    }

    /** Only what the caller may see: this is all an agent is ever shown. */
    public record CatalogView(List<Dataset> datasets) {
        public CatalogView { datasets = List.copyOf(datasets); }
        public Optional<Dataset> dataset(String id) {
            return datasets.stream().filter(d -> d.id().equals(id)).findFirst();
        }
    }

    private final Map<String, Dataset> datasets;

    public SemanticModel(Collection<Dataset> datasets) {
        Map<String, Dataset> sorted = new TreeMap<>();
        for (Dataset d : Objects.requireNonNull(datasets)) {
            if (sorted.putIfAbsent(d.id(), Objects.requireNonNull(d)) != null)
                throw new IllegalArgumentException("duplicate dataset " + d.id());
        }
        this.datasets = Collections.unmodifiableMap(sorted);
    }

    public Optional<Dataset> dataset(String id) { return Optional.ofNullable(datasets.get(id)); }

    /** Dataset lookup that treats "missing" and "not permitted" identically, so existence never leaks. */
    public Optional<Dataset> authorized(String workspaceId, String subjectId, String datasetId, DatasetPolicy policy) {
        return dataset(datasetId)
            .filter(d -> policy.allows(workspaceId, subjectId, d.sourceId(), d.id()));
    }

    public CatalogView viewFor(String workspaceId, String subjectId, DatasetPolicy policy) {
        QueryContracts.required(workspaceId, "workspaceId");
        QueryContracts.required(subjectId, "subjectId");
        return new CatalogView(datasets.values().stream()
            .filter(d -> policy.allows(workspaceId, subjectId, d.sourceId(), d.id())).toList());
    }

    static void identifier(String value, String name) {
        if (value == null || !IDENTIFIER.matcher(value).matches())
            throw new IllegalArgumentException("invalid " + name);
    }
}
