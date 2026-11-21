package tech.kayys.farabi.jdbc.source;

import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import tech.kayys.farabi.engine.DataSourceContracts.ConnectionDefinition;
import tech.kayys.farabi.engine.DataSourceContracts.ProviderKind;
import tech.kayys.farabi.engine.DataSourceContracts.SecretReference;
import tech.kayys.farabi.engine.DataSourcePorts.ConnectionRegistry;

/** JDBC-backed, workspace-scoped persistence for source definitions and non-secret properties. */
public final class JdbcConnectionRegistry implements ConnectionRegistry {
    private final DataSource dataSource;

    public JdbcConnectionRegistry(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public boolean register(ConnectionDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        String insert = "INSERT INTO farabi_data_sources "
                + "(source_id, workspace_id, display_name, provider_kind, provider_key, endpoint, "
                + "catalog_name, schema_name, credential_ref, read_only, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement(insert)) {
                    bindDefinition(statement, definition);
                    statement.executeUpdate();
                }
                try (PreparedStatement property = connection.prepareStatement(
                        "INSERT INTO farabi_data_source_properties "
                                + "(source_id, workspace_id, property_key, property_value) VALUES (?, ?, ?, ?)")) {
                    for (Map.Entry<String, String> entry : definition.properties().entrySet()) {
                        property.setString(1, definition.sourceId());
                        property.setString(2, definition.workspaceId());
                        property.setString(3, entry.getKey());
                        property.setString(4, entry.getValue());
                        property.addBatch();
                    }
                    property.executeBatch();
                }
                connection.commit();
                return true;
            } catch (SQLException failure) {
                connection.rollback();
                if (isUniqueViolation(failure)) return false;
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("register data source failed", failure);
        }
    }

    @Override
    public Optional<ConnectionDefinition> find(String sourceId, String workspaceId) {
        requireScope(sourceId, workspaceId);
        String sql = "SELECT source_id, workspace_id, display_name, provider_kind, provider_key, endpoint, "
                + "catalog_name, schema_name, credential_ref, read_only, created_at "
                + "FROM farabi_data_sources WHERE source_id=? AND workspace_id=?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sourceId);
            statement.setString(2, workspaceId);
            StoredDefinition stored;
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                stored = readStoredDefinition(rows);
            }
            return Optional.of(toDefinition(connection, stored));
        } catch (SQLException failure) {
            throw new IllegalStateException("find data source failed", failure);
        }
    }

    @Override
    public List<ConnectionDefinition> list(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) throw new IllegalArgumentException("workspaceId required");
        String sql = "SELECT source_id, workspace_id, display_name, provider_kind, provider_key, endpoint, "
                + "catalog_name, schema_name, credential_ref, read_only, created_at "
                + "FROM farabi_data_sources WHERE workspace_id=? ORDER BY source_id";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, workspaceId);
            List<StoredDefinition> stored = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) stored.add(readStoredDefinition(rows));
            }
            List<ConnectionDefinition> result = new ArrayList<>(stored.size());
            for (StoredDefinition definition : stored) result.add(toDefinition(connection, definition));
            return List.copyOf(result);
        } catch (SQLException failure) {
            throw new IllegalStateException("list data sources failed", failure);
        }
    }

    @Override
    public boolean remove(String sourceId, String workspaceId) {
        requireScope(sourceId, workspaceId);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM farabi_data_sources WHERE source_id=? AND workspace_id=?")) {
            statement.setString(1, sourceId);
            statement.setString(2, workspaceId);
            return statement.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new IllegalStateException("remove data source failed", failure);
        }
    }

    private ConnectionDefinition toDefinition(Connection connection, StoredDefinition row) throws SQLException {
        Map<String, String> properties = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT property_key, property_value FROM farabi_data_source_properties "
                        + "WHERE source_id=? AND workspace_id=? ORDER BY property_key")) {
            statement.setString(1, row.sourceId());
            statement.setString(2, row.workspaceId());
            try (ResultSet values = statement.executeQuery()) {
                while (values.next()) properties.put(values.getString(1), values.getString(2));
            }
        }
        return new ConnectionDefinition(row.sourceId(), row.workspaceId(), row.displayName(),
                row.providerKind(), row.providerKey(), row.endpoint(), row.catalog(), row.schema(),
                properties, row.credential() == null ? null : new SecretReference(row.credential()),
                row.readOnly(), row.createdAt());
    }

    private static StoredDefinition readStoredDefinition(ResultSet row) throws SQLException {
        String credential = row.getString("credential_ref");
        return new StoredDefinition(row.getString("source_id"), row.getString("workspace_id"),
                row.getString("display_name"), ProviderKind.valueOf(row.getString("provider_kind")),
                row.getString("provider_key"), URI.create(row.getString("endpoint")),
                row.getString("catalog_name"), row.getString("schema_name"), credential,
                row.getBoolean("read_only"), row.getTimestamp("created_at").toInstant());
    }

    private record StoredDefinition(
            String sourceId, String workspaceId, String displayName, ProviderKind providerKind,
            String providerKey, URI endpoint, String catalog, String schema, String credential,
            boolean readOnly, Instant createdAt) {}

    private static void bindDefinition(PreparedStatement statement, ConnectionDefinition definition)
            throws SQLException {
        statement.setString(1, definition.sourceId());
        statement.setString(2, definition.workspaceId());
        statement.setString(3, definition.displayName());
        statement.setString(4, definition.providerKind().name());
        statement.setString(5, definition.providerKey());
        statement.setString(6, definition.endpoint().toString());
        statement.setString(7, definition.catalog());
        statement.setString(8, definition.schema());
        statement.setString(9, definition.credential() == null ? null : definition.credential().reference());
        statement.setBoolean(10, definition.readOnly());
        statement.setTimestamp(11, Timestamp.from(definition.createdAt()));
    }

    private static boolean isUniqueViolation(SQLException failure) {
        for (SQLException current = failure; current != null; current = current.getNextException()) {
            String state = current.getSQLState();
            if ("23505".equals(state) || current.getErrorCode() == 1062) return true;
        }
        return false;
    }

    private static void requireScope(String sourceId, String workspaceId) {
        if (sourceId == null || sourceId.isBlank()) throw new IllegalArgumentException("sourceId required");
        if (workspaceId == null || workspaceId.isBlank()) throw new IllegalArgumentException("workspaceId required");
    }
}
