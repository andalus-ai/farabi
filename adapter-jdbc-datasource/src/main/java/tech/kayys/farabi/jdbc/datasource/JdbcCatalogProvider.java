package tech.kayys.farabi.jdbc.datasource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import tech.kayys.farabi.engine.DataSourceContracts.CatalogEntry;
import tech.kayys.farabi.engine.DataSourceContracts.ColumnMetadata;
import tech.kayys.farabi.engine.DataSourceContracts.EntryKind;
import tech.kayys.farabi.engine.DataSourceContracts.LogicalType;
import tech.kayys.farabi.engine.DataSourcePorts.CatalogProvider;

/** JDBC metadata discovery; identifiers are returned as metadata, never interpolated into SQL. */
public final class JdbcCatalogProvider implements CatalogProvider {
    private final JdbcDataSourceRegistry registry;

    public JdbcCatalogProvider(JdbcDataSourceRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override public List<CatalogEntry> listObjects(String sourceId, String catalog, String schemaPattern) {
        try (Connection connection = registry.requireDataSource(sourceId).getConnection();
             ResultSet rs = connection.getMetaData().getTables(catalog, schemaPattern, "%",
                     new String[]{"TABLE", "VIEW", "MATERIALIZED VIEW"})) {
            List<CatalogEntry> objects = new ArrayList<>();
            while (rs.next()) {
                String cat = rs.getString("TABLE_CAT");
                String schema = rs.getString("TABLE_SCHEM");
                String name = rs.getString("TABLE_NAME");
                String type = rs.getString("TABLE_TYPE");
                EntryKind kind = "TABLE".equalsIgnoreCase(type) ? EntryKind.TABLE : EntryKind.VIEW;
                String id = String.join(".", java.util.stream.Stream.of(cat, schema, name)
                        .filter(Objects::nonNull).toList());
                objects.add(new CatalogEntry(id, name, kind));
            }
            return List.copyOf(objects);
        } catch (SQLException e) {
            throw new IllegalStateException("JDBC catalog discovery failed for source " + sourceId, e);
        }
    }

    @Override public List<ColumnMetadata> listColumns(String sourceId, String catalog, String schema, String objectName) {
        if (objectName == null || objectName.isBlank()) throw new IllegalArgumentException("objectName required");
        try (Connection connection = registry.requireDataSource(sourceId).getConnection();
             ResultSet rs = connection.getMetaData().getColumns(catalog, schema, objectName, "%")) {
            List<ColumnMetadata> columns = new ArrayList<>();
            while (rs.next()) {
                columns.add(new ColumnMetadata(rs.getString("COLUMN_NAME"),
                        logicalType(rs.getInt("DATA_TYPE")), rs.getInt("NULLABLE") != java.sql.DatabaseMetaData.columnNoNulls));
            }
            return List.copyOf(columns);
        } catch (SQLException e) {
            throw new IllegalStateException("JDBC column discovery failed for source " + sourceId, e);
        }
    }

    private static LogicalType logicalType(int jdbcType) {
        return switch (jdbcType) {
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR,
                    Types.LONGNVARCHAR, Types.CLOB, Types.NCLOB -> LogicalType.STRING;
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> LogicalType.INTEGER;
            case Types.NUMERIC, Types.DECIMAL, Types.REAL, Types.FLOAT, Types.DOUBLE -> LogicalType.DECIMAL;
            case Types.BOOLEAN, Types.BIT -> LogicalType.BOOLEAN;
            case Types.DATE -> LogicalType.DATE;
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> LogicalType.TIMESTAMP;
            case Types.TIME, Types.TIME_WITH_TIMEZONE -> LogicalType.TIME;
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> LogicalType.BINARY;
            default -> LogicalType.UNKNOWN;
        };
    }
}
