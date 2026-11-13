package tech.kayys.farabi.jdbc.source;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import tech.kayys.farabi.engine.DataSourceContracts.*;
import tech.kayys.farabi.engine.DataSourcePorts.Provider;
import tech.kayys.farabi.engine.DatasetContracts.LogicalType;

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

    @Override public String providerKey() { return PROVIDER_KEY; }

    @Override public CapabilitySet capabilities(ConnectionDefinition definition) {
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

    @Override public SourceHealth testConnection(ConnectionDefinition definition) {
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

    @Override public List<String> catalogs(ConnectionDefinition definition) {
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

    @Override public List<String> schemas(ConnectionDefinition definition, String catalog) {
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

    @Override public List<CatalogEntry> datasets(ConnectionDefinition definition, String catalog, String schema) {
        return withConnection(definition, connection -> {
            List<CatalogEntry> result = new ArrayList<>();
            try (ResultSet rows = connection.getMetaData().getTables(catalog, schema, null, TABLE_TYPES)) {
                while (rows.next()) {
                    result.add(new CatalogEntry(rows.getString("TABLE_CAT"),
                            rows.getString("TABLE_SCHEM"), rows.getString("TABLE_NAME"),
                            rows.getString("TABLE_TYPE")));
                }
            }
            return List.copyOf(result);
        });
    }

    @Override public List<ColumnMetadata> columns(ConnectionDefinition definition, String catalog,
                                                   String schema, String dataset) {
        if (dataset == null || dataset.isBlank()) throw new IllegalArgumentException("dataset required");
        return withConnection(definition, connection -> {
            List<ColumnMetadata> result = new ArrayList<>();
            try (ResultSet rows = connection.getMetaData().getColumns(catalog, schema, dataset, null)) {
                while (rows.next()) {
                    result.add(new ColumnMetadata(rows.getString("COLUMN_NAME"),
                            rows.getString("TYPE_NAME"), logicalType(rows.getInt("DATA_TYPE"),
                            rows.getString("TYPE_NAME")), rows.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                            rows.getInt("ORDINAL_POSITION")));
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
