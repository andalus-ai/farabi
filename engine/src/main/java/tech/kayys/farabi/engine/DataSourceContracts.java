package tech.kayys.farabi.engine;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Provider-neutral datasource discovery and capability contracts.
 *
 * <p>These contracts describe a configured source; they deliberately contain no
 * connection credentials, JDBC types, vendor SDK types, or runtime framework types.
 * Authorization and query governance remain owned by the Farabi engine.</p>
 */
public final class DataSourceContracts {
    private DataSourceContracts() {}

    public record Descriptor(
        String sourceId,
        String providerId,
        String displayName,
        String providerVersion
    ) {
        public Descriptor {
            QueryContracts.required(sourceId, "sourceId");
            QueryContracts.required(providerId, "providerId");
            QueryContracts.required(displayName, "displayName");
            QueryContracts.required(providerVersion, "providerVersion");
        }
    }

    public enum Feature {
        PARAMETERIZED_QUERIES,
        CANCELLATION,
        RESULT_PAGING,
        SCHEMAS,
        VIEWS,
        DATE_TRUNCATION,
        OFFSET_PAGINATION
    }

    public record Capabilities(
        Set<Feature> features,
        int maximumRows,
        int maximumPageSize
    ) {
        public Capabilities {
            features = Set.copyOf(Objects.requireNonNull(features, "features"));
            if (maximumRows < 1) throw new IllegalArgumentException("maximumRows must be positive");
            if (maximumPageSize < 1 || maximumPageSize > maximumRows)
                throw new IllegalArgumentException("maximumPageSize must be between 1 and maximumRows");
        }

        public boolean supports(Feature feature) {
            return features.contains(Objects.requireNonNull(feature, "feature"));
        }
    }

    public enum EntryKind { CATALOG, SCHEMA, TABLE, VIEW }

    public record CatalogEntry(String id, String name, EntryKind kind) {
        public CatalogEntry {
            QueryContracts.required(id, "id");
            QueryContracts.required(name, "name");
            Objects.requireNonNull(kind, "kind");
        }
    }

    public enum LogicalType {
        STRING, INTEGER, DECIMAL, BOOLEAN, DATE, TIMESTAMP, TIME, BINARY, UNKNOWN
    }

    public record ColumnMetadata(
        String name,
        LogicalType type,
        boolean nullable
    ) {
        public ColumnMetadata {
            QueryContracts.required(name, "name");
            Objects.requireNonNull(type, "type");
        }
    }

    public record RelationMetadata(
        String relationId,
        String catalog,
        String schema,
        String name,
        EntryKind kind,
        List<ColumnMetadata> columns
    ) {
        public RelationMetadata {
            QueryContracts.required(relationId, "relationId");
            QueryContracts.required(name, "name");
            Objects.requireNonNull(kind, "kind");
            if (kind != EntryKind.TABLE && kind != EntryKind.VIEW)
                throw new IllegalArgumentException("relation kind must be TABLE or VIEW");
            columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
        }
    }

    public enum HealthStatus { AVAILABLE, DEGRADED, UNAVAILABLE, UNKNOWN }

    public record Health(HealthStatus status, String diagnostic) {
        public Health {
            Objects.requireNonNull(status, "status");
            diagnostic = diagnostic == null ? "" : diagnostic;
        }
    }

    public interface Catalog {
        List<CatalogEntry> catalogs();
        List<CatalogEntry> schemas(String catalog);
        List<CatalogEntry> relations(String catalog, String schema);
        RelationMetadata describe(String relationId);
    }

    /** A source provider adapts discovery and remote job lifecycle to engine-owned ports. */
    public interface Provider {
        Descriptor descriptor();
        Capabilities capabilities();
        Catalog catalog();
        /** Legacy plan executor retained while existing providers migrate. */
        QueryPorts.Executor executor();
        QueryPorts.ParameterizedExecutor parameterizedExecutor();
        QueryLifecycle.Provider lifecycle();
        Health health();
    }

    public interface Registry {
        Provider require(String sourceId);
        List<Descriptor> availableSources();
    }
}
