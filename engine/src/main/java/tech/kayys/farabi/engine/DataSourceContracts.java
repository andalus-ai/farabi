package tech.kayys.farabi.engine;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Provider-neutral contracts for externally managed analytical data sources.
 *
 * Credentials are deliberately represented by secret references, never by secret values.
 * These contracts describe connection configuration; they do not grant query authorization.
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
            if (endpoint.getUserInfo() != null || endpoint.getRawQuery() != null
                    || endpoint.getRawFragment() != null) {
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

    public record CatalogEntry(String catalog, String schema, String name, String type) {
        public CatalogEntry {
            QueryContracts.required(name, "name");
            QueryContracts.required(type, "type");
        }
    }

    public record ColumnMetadata(
            String name, String nativeType, DatasetContracts.LogicalType logicalType,
            boolean nullable, Integer ordinalPosition) {
        public ColumnMetadata {
            QueryContracts.required(name, "name");
            QueryContracts.required(nativeType, "nativeType");
            Objects.requireNonNull(logicalType, "logicalType");
            if (ordinalPosition != null && ordinalPosition < 1) {
                throw new IllegalArgumentException("ordinalPosition must be positive");
            }
        }
    }
}
