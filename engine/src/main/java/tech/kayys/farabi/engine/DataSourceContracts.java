package tech.kayys.farabi.engine;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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

    public enum ProviderKind { JDBC, DREMIO, TRINO, CLICKHOUSE, DUCKDB, CUSTOM }
    public enum Health { UNKNOWN, AVAILABLE, UNAVAILABLE, DEGRADED }

    public record SecretReference(String reference) {
        public SecretReference { QueryContracts.required(reference, "secret reference"); }
    }

    public record ConnectionDefinition(
            String sourceId,
            String workspaceId,
            String displayName,
            ProviderKind providerKind,
            String providerKey,
            URI endpoint,
            String catalog,
            String schema,
            Map<String, String> properties,
            SecretReference credential,
            boolean readOnly,
            Instant createdAt) {
        public ConnectionDefinition {
            QueryContracts.required(sourceId, "sourceId");
            QueryContracts.required(workspaceId, "workspaceId");
            QueryContracts.required(displayName, "displayName");
            Objects.requireNonNull(providerKind, "providerKind");
            QueryContracts.required(providerKey, "providerKey");
            Objects.requireNonNull(endpoint, "endpoint");
            if (!"https".equalsIgnoreCase(endpoint.getScheme())
                    && !"jdbc".equalsIgnoreCase(endpoint.getScheme())
                    && !"http".equalsIgnoreCase(endpoint.getScheme())) {
                throw new IllegalArgumentException("unsupported endpoint scheme");
            }
            URI networkEndpoint = endpoint;
            if ("jdbc".equalsIgnoreCase(endpoint.getScheme())) {
                String nested = endpoint.getRawSchemeSpecificPart();
                if (nested == null || nested.isBlank()) {
                    throw new IllegalArgumentException("JDBC endpoint is required");
                }
                networkEndpoint = URI.create(nested);
            }
            if (networkEndpoint.getUserInfo() != null || networkEndpoint.getRawQuery() != null
                    || networkEndpoint.getRawFragment() != null) {
                throw new IllegalArgumentException("endpoint must not contain credentials, query, or fragment");
            }
            properties = Map.copyOf(Objects.requireNonNull(properties, "properties"));
            if (properties.keySet().stream().anyMatch(k -> k == null || k.isBlank())
                    || properties.values().stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("connection properties must have non-null keys and values");
            }
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    public record CapabilitySet(
            boolean catalogDiscovery,
            boolean schemaDiscovery,
            boolean tableDiscovery,
            boolean queryExecution,
            boolean asynchronousJobs,
            boolean cancellation,
            boolean parameterBinding,
            boolean transactions,
            List<String> supportedLogicalTypes,
            String dialectId) {
        public CapabilitySet {
            supportedLogicalTypes = List.copyOf(Objects.requireNonNull(supportedLogicalTypes, "supportedLogicalTypes"));
            QueryContracts.required(dialectId, "dialectId");
        }
    }

    public record SourceHealth(Health health, Instant checkedAt, String diagnostic) {
        public SourceHealth {
            Objects.requireNonNull(health, "health");
            Objects.requireNonNull(checkedAt, "checkedAt");
        }
    }

    public record Descriptor(String sourceId, String providerId, String displayName, String providerVersion) {
        public Descriptor {
            QueryContracts.required(sourceId, "sourceId");
            QueryContracts.required(providerId, "providerId");
            QueryContracts.required(displayName, "displayName");
            QueryContracts.required(providerVersion, "providerVersion");
        }
    }

    public enum Feature {
        PARAMETERIZED_QUERIES, CANCELLATION, RESULT_PAGING, SCHEMAS, VIEWS,
        DATE_TRUNCATION, OFFSET_PAGINATION
    }

    public record Capabilities(Set<Feature> features, int maximumRows, int maximumPageSize) {
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

    /**
     * Provider-neutral relation identity. The three-argument constructor is the
     * canonical engine API; the four-argument constructor preserves catalog/schema
     * details supplied by JDBC metadata adapters.
     */
    public record CatalogEntry(
            String id, String name, EntryKind kind, String catalog, String schema) {
        public CatalogEntry(String id, String name, EntryKind kind) {
            this(id, name, kind, null, null);
        }

        public CatalogEntry(String catalog, String schema, String name, String nativeKind) {
            this(relationId(catalog, schema, name), name, entryKind(nativeKind), catalog, schema);
        }

        public CatalogEntry {
            QueryContracts.required(id, "id");
            QueryContracts.required(name, "name");
            Objects.requireNonNull(kind, "kind");
        }

        private static String relationId(String catalog, String schema, String name) {
            QueryContracts.required(name, "name");
            return java.util.stream.Stream.of(catalog, schema, name)
                    .filter(value -> value != null && !value.isBlank())
                    .collect(java.util.stream.Collectors.joining("."));
        }

        private static EntryKind entryKind(String nativeKind) {
            return nativeKind != null && nativeKind.equalsIgnoreCase("TABLE")
                    ? EntryKind.TABLE : EntryKind.VIEW;
        }
    }

    public enum LogicalType {
        STRING, INTEGER, DECIMAL, BOOLEAN, DATE, TIMESTAMP, TIME, BINARY, JSON, UNKNOWN
    }

    /**
     * Column metadata retains native type and ordinal when supplied, while keeping
     * the compact three-argument constructor for providers with normalized metadata.
     */
    public record ColumnMetadata(
            String name, LogicalType logicalType, boolean nullable,
            String nativeType, int ordinalPosition) {
        public ColumnMetadata(String name, LogicalType logicalType, boolean nullable) {
            this(name, logicalType, nullable, null, 0);
        }

        public ColumnMetadata(String name, String nativeType, LogicalType logicalType,
                              boolean nullable, int ordinalPosition) {
            this(name, logicalType, nullable, nativeType, ordinalPosition);
        }

        public ColumnMetadata {
            QueryContracts.required(name, "name");
            Objects.requireNonNull(logicalType, "logicalType");
            if (ordinalPosition < 0) {
                throw new IllegalArgumentException("ordinalPosition cannot be negative");
            }
        }

        public LogicalType type() {
            return logicalType;
        }
    }

        public record RelationMetadata(
            String relationId, String catalog, String schema, String name,
            EntryKind kind, List<ColumnMetadata> columns) {
        public RelationMetadata {
            QueryContracts.required(relationId, "relationId");
            QueryContracts.required(name, "name");
            Objects.requireNonNull(kind, "kind");
            if (kind != EntryKind.TABLE && kind != EntryKind.VIEW)
                throw new IllegalArgumentException("relation kind must be TABLE or VIEW");
            columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
        }
    }

    public interface Catalog {
        List<CatalogEntry> catalogs();
        List<CatalogEntry> schemas(String catalog);
        List<CatalogEntry> relations(String catalog, String schema);
        RelationMetadata describe(String relationId);
    }

    /** A source provider adapts discovery and remote job lifecycle to engine-owned ports. */
    /**
     * Provider adapter selected by providerKey. Methods receive the persisted
     * connection definition so one adapter instance can safely serve many
     * workspace-scoped sources without hidden mutable connection state.
     */
    public interface Provider {
        Descriptor descriptor(ConnectionDefinition definition);
        Capabilities capabilities(ConnectionDefinition definition);
        Catalog catalog(ConnectionDefinition definition);
        default QueryPorts.Executor executor(ConnectionDefinition definition) {
            throw new UnsupportedOperationException("provider does not expose legacy query execution");
        }
        default QueryPorts.ParameterizedExecutor parameterizedExecutor(ConnectionDefinition definition) {
            throw new UnsupportedOperationException("provider does not expose parameterized query execution");
        }
        default QueryLifecycle.Provider lifecycle(ConnectionDefinition definition) {
            throw new UnsupportedOperationException("provider does not expose remote query lifecycle");
        }
        SourceHealth health(ConnectionDefinition definition);
    }

    public interface Registry {
        List<Descriptor> availableSources();
    }
}
