package tech.kayys.farabi.jdbc.source;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import tech.kayys.farabi.engine.DataSourceContracts.*;
import tech.kayys.farabi.engine.DataSourcePorts.Provider;
import tech.kayys.farabi.engine.DataSourceContracts.LogicalType;

/**
 * Generic JDBC metadata provider. Connection creation (including secret resolution,
 * TLS policy, pooling and driver selection) is injected by the hosting runtime.
 */
public final class JdbcSourceProvider implements Provider {
    public static final String PROVIDER_KEY = "jdbc";
    private static final String[] TABLE_TYPES = {"TABLE", "VIEW", "MATERIALIZED VIEW"};

    @FunctionalInterface
    public interface ConnectionFactory {
        Connection open(ConnectionDefinition definition) throws SQLException;
    }

    private final ConnectionFactory connections;

    public JdbcSourceProvider(ConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    public String providerKey() { return PROVIDER_KEY; }

    /** Opens a provider-owned connection for a separately governed execution adapter. */
    public Connection openConnection(ConnectionDefinition definition) throws SQLException {
        Objects.requireNonNull(definition, "definition");
        Connection connection = connections.open(definition);
        if (connection == null) throw new IllegalStateException("JDBC connection factory returned null");
        try {
            if (definition.readOnly()) connection.setReadOnly(true);
            return connection;
        } catch (SQLException | RuntimeException failure) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    /** Detailed vendor capability probe retained for diagnostics and dialect selection. */
    public CapabilitySet capabilitySet(ConnectionDefinition definition) {
        return withConnection(definition, connection -> {
            DatabaseMetaData metadata = connection.getMetaData();
            String product = metadata.getDatabaseProductName();
            String dialect = dialectId(product);
            return new CapabilitySet(true, true, true, false, false, false,
                    false, metadata.supportsTransactions(), List.of(
                    "BOOLEAN", "INTEGER", "DECIMAL", "STRING", "DATE", "TIME",
                    "TIMESTAMP", "BINARY", "JSON", "UNKNOWN"), dialect);
        });
    }

    @Override
    public Descriptor descriptor(ConnectionDefinition definition) {
        requireJdbc(definition);
        return new Descriptor(definition.sourceId(), PROVIDER_KEY, definition.displayName(), "jdbc-generic-v1");
    }

    @Override
    public Capabilities capabilities(ConnectionDefinition definition) {
        requireJdbc(definition);
        CapabilitySet probed = capabilitySet(definition);
        Set<Feature> features = EnumSet.noneOf(Feature.class);
        if (probed.schemaDiscovery()) features.add(Feature.SCHEMAS);
        if (probed.tableDiscovery()) features.add(Feature.VIEWS);
        // Query execution is supplied by the separate governed JdbcQueryProvider,
        // not by this metadata adapter.
        return new Capabilities(features, 1, 1);
    }

    @Override
    public Catalog catalog(ConnectionDefinition definition) {
        requireJdbc(definition);
        return new Catalog() {
            @Override
            public List<CatalogEntry> catalogs() {
                return JdbcSourceProvider.this.catalogs(definition).stream()
                        .map(name -> new CatalogEntry(name, name, EntryKind.CATALOG)).toList();
            }

            @Override
            public List<CatalogEntry> schemas(String catalog) {
                return JdbcSourceProvider.this.schemas(definition, catalog).stream()
                        .map(name -> new CatalogEntry(catalog + "/" + name, name, EntryKind.SCHEMA)).toList();
            }

            @Override
            public List<CatalogEntry> relations(String catalog, String schema) {
                return JdbcSourceProvider.this.datasets(definition, catalog, schema);
            }

            @Override
            public RelationMetadata describe(String relationId) {
                String[] parts = decodeRelationId(relationId);
                String catalog = emptyToNull(parts[0]);
                String schema = emptyToNull(parts[1]);
                String name = parts[2];
                CatalogEntry entry = JdbcSourceProvider.this.datasets(definition, catalog, schema).stream()
                        .filter(candidate -> candidate.id().equals(relationId))
                        .findFirst().orElseThrow(() -> new IllegalArgumentException("JDBC relation not found"));
                return new RelationMetadata(relationId, catalog, schema, name, entry.kind(),
                        JdbcSourceProvider.this.columns(definition, catalog, schema, name));
            }
        };
    }

    @Override
    public SourceHealth health(ConnectionDefinition definition) {
        return testConnection(definition);
    }

    public SourceHealth testConnection(ConnectionDefinition definition) {
        Instant checkedAt = Instant.now();
        try {
            withConnection(definition, connection -> {
                if (!connection.isValid(5)) throw new SQLException("connection validation failed", "08006");
                return null;
            });
            return new SourceHealth(Health.AVAILABLE, checkedAt, null);
        } catch (RuntimeException failure) {
            String state = findSqlState(failure);
            return new SourceHealth(Health.UNAVAILABLE, checkedAt,
                    state == null ? "connection failed" : "connection failed (SQLState " + state + ")");
        }
    }

    public List<String> catalogs(ConnectionDefinition definition) {
        return withConnection(definition, connection -> {
            List<String> result = new ArrayList<>();
            try (ResultSet rows = connection.getMetaData().getCatalogs()) {
                while (rows.next()) {
                    String catalog = rows.getString("TABLE_CAT");
                    if (catalog != null && !catalog.isBlank()) result.add(catalog);
                }
            }
            return List.copyOf(result);
        });
    }

    public List<String> schemas(ConnectionDefinition definition, String catalog) {
        return withConnection(definition, connection -> {
            List<String> result = new ArrayList<>();
            try (ResultSet rows = connection.getMetaData().getSchemas(catalog, null)) {
                while (rows.next()) {
                    String schema = rows.getString("TABLE_SCHEM");
                    if (schema != null && !schema.isBlank()) result.add(schema);
                }
            }
            return List.copyOf(result);
        });
    }

    public List<CatalogEntry> datasets(ConnectionDefinition definition, String catalog, String schema) {
        return withConnection(definition, connection -> {
            List<CatalogEntry> result = new ArrayList<>();
            try (ResultSet rows = connection.getMetaData().getTables(catalog, schema, null, TABLE_TYPES)) {
                while (rows.next()) {
                    String tableCatalog = rows.getString("TABLE_CAT");
                    String tableSchema = rows.getString("TABLE_SCHEM");
                    String tableName = rows.getString("TABLE_NAME");
                    String tableType = rows.getString("TABLE_TYPE");
                    EntryKind kind = tableType != null && tableType.toUpperCase(Locale.ROOT).contains("VIEW")
                            ? EntryKind.VIEW : EntryKind.TABLE;
                    result.add(new CatalogEntry(encodeRelationId(tableCatalog, tableSchema, tableName),
                            tableName, kind));
                }
            }
            return List.copyOf(result);
        });
    }

    public List<ColumnMetadata> columns(ConnectionDefinition definition, String catalog,
                                        String schema, String dataset) {
        if (dataset == null || dataset.isBlank()) throw new IllegalArgumentException("dataset required");
        return withConnection(definition, connection -> {
            List<ColumnMetadata> result = new ArrayList<>();
            try (ResultSet rows = connection.getMetaData().getColumns(catalog, schema, dataset, null)) {
                while (rows.next()) {
                    result.add(new ColumnMetadata(rows.getString("COLUMN_NAME"),
                            logicalType(rows.getInt("DATA_TYPE"), rows.getString("TYPE_NAME")),
                            rows.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls));
                }
            }
            return List.copyOf(result);
        });
    }

    private <T> T withConnection(ConnectionDefinition definition, SqlWork<T> work) {
        Objects.requireNonNull(definition, "definition");
        try (Connection connection = connections.open(definition)) {
            if (connection == null) throw new IllegalStateException("JDBC connection factory returned null");
            if (definition.readOnly()) connection.setReadOnly(true);
            return work.apply(connection);
        } catch (SQLException e) {
            throw new IllegalStateException("JDBC source operation failed (SQLState " + e.getSQLState() + ")", e);
        }
    }

    private static void requireJdbc(ConnectionDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        if (definition.providerKind() != ProviderKind.JDBC || !PROVIDER_KEY.equals(definition.providerKey())) {
            throw new IllegalArgumentException("connection definition is not configured for generic JDBC");
        }
    }

    private static String encodeRelationId(String catalog, String schema, String name) {
        String value = Objects.toString(catalog, "") + "\u0000"
                + Objects.toString(schema, "") + "\u0000" + Objects.toString(name, "");
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String[] decodeRelationId(String relationId) {
        if (relationId == null || relationId.isBlank()) throw new IllegalArgumentException("relationId required");
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(relationId), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\u0000", -1);
            if (parts.length != 3 || parts[2].isBlank()) throw new IllegalArgumentException("invalid JDBC relation id");
            return parts;
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("invalid JDBC relation id", invalid);
        }
    }

    private static String emptyToNull(String value) { return value == null || value.isEmpty() ? null : value; }

    private static String dialectId(String product) {
        String name = product == null ? "" : product.toLowerCase(Locale.ROOT);
        if (name.contains("postgres")) return "postgresql";
        if (name.contains("mysql") || name.contains("mariadb")) return "mysql";
        if (name.contains("h2")) return "h2";
        return "jdbc-generic";
    }

    static LogicalType logicalType(int jdbcType, String nativeType) {
        String type = nativeType == null ? "" : nativeType.toLowerCase(Locale.ROOT);
        if (type.equals("json") || type.equals("jsonb")) return LogicalType.JSON;
        return switch (jdbcType) {
            case java.sql.Types.BIT, java.sql.Types.BOOLEAN -> LogicalType.BOOLEAN;
            case java.sql.Types.TINYINT, java.sql.Types.SMALLINT, java.sql.Types.INTEGER,
                    java.sql.Types.BIGINT -> LogicalType.INTEGER;
            case java.sql.Types.NUMERIC, java.sql.Types.DECIMAL, java.sql.Types.REAL,
                    java.sql.Types.FLOAT, java.sql.Types.DOUBLE -> LogicalType.DECIMAL;
            case java.sql.Types.CHAR, java.sql.Types.VARCHAR, java.sql.Types.LONGVARCHAR,
                    java.sql.Types.NCHAR, java.sql.Types.NVARCHAR, java.sql.Types.LONGNVARCHAR,
                    java.sql.Types.CLOB, java.sql.Types.NCLOB -> LogicalType.STRING;
            case java.sql.Types.DATE -> LogicalType.DATE;
            case java.sql.Types.TIME, java.sql.Types.TIME_WITH_TIMEZONE -> LogicalType.TIME;
            case java.sql.Types.TIMESTAMP, java.sql.Types.TIMESTAMP_WITH_TIMEZONE -> LogicalType.TIMESTAMP;
            case java.sql.Types.BINARY, java.sql.Types.VARBINARY, java.sql.Types.LONGVARBINARY,
                    java.sql.Types.BLOB -> LogicalType.BINARY;
            case java.sql.Types.OTHER, java.sql.Types.JAVA_OBJECT, java.sql.Types.SQLXML ->
                    type.contains("json") ? LogicalType.JSON : LogicalType.UNKNOWN;
            default -> LogicalType.UNKNOWN;
        };
    }

    private static String findSqlState(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql && sql.getSQLState() != null) return sql.getSQLState();
        }
        return null;
    }

    @FunctionalInterface private interface SqlWork<T> { T apply(Connection connection) throws SQLException; }
}
