package tech.kayys.farabi.jdbc.datasource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import tech.kayys.farabi.engine.DataSourceContracts.CatalogObject;
import tech.kayys.farabi.engine.DataSourceContracts.ColumnMetadata;
import tech.kayys.farabi.engine.DataSourcePorts.CatalogProvider;

/** JDBC metadata discovery; identifiers are returned as metadata, never interpolated into SQL. */
public final class JdbcCatalogProvider implements CatalogProvider {
    private final JdbcDataSourceRegistry registry;

    public JdbcCatalogProvider(JdbcDataSourceRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override public List<CatalogObject> listObjects(String sourceId, String catalog, String schemaPattern) {
        try (Connection connection = registry.requireDataSource(sourceId).getConnection();
             ResultSet rs = connection.getMetaData().getTables(catalog, schemaPattern, "%", new String[]{"TABLE", "VIEW", "MATERIALIZED VIEW"})) {
            List<CatalogObject> objects = new ArrayList<>();
            while (rs.next()) objects.add(new CatalogObject(
                    rs.getString("TABLE_CAT"), rs.getString("TABLE_SCHEM"),
                    rs.getString("TABLE_NAME"), rs.getString("TABLE_TYPE")));
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
            while (rs.next()) columns.add(new ColumnMetadata(
                    rs.getString("COLUMN_NAME"), rs.getString("TYPE_NAME"),
                    rs.getInt("DATA_TYPE"), rs.getInt("NULLABLE") != 0,
                    rs.getInt("ORDINAL_POSITION"), rs.getInt("COLUMN_SIZE"), rs.getInt("DECIMAL_DIGITS")));
            return List.copyOf(columns);
        } catch (SQLException e) {
            throw new IllegalStateException("JDBC column discovery failed for source " + sourceId, e);
        }
    }
}
