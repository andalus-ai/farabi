package tech.kayys.farabi.engine;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Provider-neutral contracts for registered analytical data sources. */
public final class DataSourceContracts {
    private DataSourceContracts() {}

    public enum ProviderKind { JDBC, DREMIO, TRINO, FILE, OBJECT_STORAGE, CUSTOM }

    public record DataSourceDescriptor(
            String id, String displayName, ProviderKind providerKind, boolean readOnly) {
        public DataSourceDescriptor {
            QueryContracts.required(id, "id");
            QueryContracts.required(displayName, "displayName");
            Objects.requireNonNull(providerKind, "providerKind");
        }
    }

    public record CatalogObject(
            String catalog, String schema, String name, String type) {
        public CatalogObject {
            QueryContracts.required(name, "name");
            QueryContracts.required(type, "type");
        }
    }

    public record ColumnMetadata(
            String name, String databaseType, int jdbcType, boolean nullable,
            int ordinalPosition, int precision, int scale) {
        public ColumnMetadata {
            QueryContracts.required(name, "name");
            QueryContracts.required(databaseType, "databaseType");
        }
    }

    public record QueryRequest(String sourceId, String sql, List<Object> parameters, int maxRows) {
        public QueryRequest {
            QueryContracts.required(sourceId, "sourceId");
            QueryContracts.required(sql, "sql");
            parameters = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(Objects.requireNonNull(parameters, "parameters")));
            if (maxRows < 1 || maxRows > 100_000) {
                throw new IllegalArgumentException("maxRows must be between 1 and 100000");
            }
        }
    }

    public record QueryResult(
            List<String> columns, List<Map<String, Object>> rows, boolean truncated) {
        public QueryResult {
            columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
            rows = rows.stream().map(row -> java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(row))).toList();
        }
    }
}
