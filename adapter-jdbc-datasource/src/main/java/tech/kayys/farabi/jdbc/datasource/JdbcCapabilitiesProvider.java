package tech.kayys.farabi.jdbc.datasource;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Objects;
import tech.kayys.farabi.engine.DataSourceCapabilities;
import tech.kayys.farabi.engine.DataSourcePorts.CapabilitiesProvider;

/** Reads capabilities from the live JDBC driver instead of guessing from a vendor name. */
public final class JdbcCapabilitiesProvider implements CapabilitiesProvider {
    private final JdbcDataSourceRegistry registry;

    public JdbcCapabilitiesProvider(JdbcDataSourceRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public DataSourceCapabilities capabilities(String sourceId) {
        try (Connection connection = registry.requireDataSource(sourceId).getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            return new DataSourceCapabilities(
                    metadata.getDatabaseProductName(),
                    metadata.getDatabaseProductVersion(),
                    metadata.getDriverName(),
                    metadata.getDriverVersion(),
                    metadata.getIdentifierQuoteString(),
                    metadata.supportsCatalogsInTableDefinitions(),
                    metadata.supportsSchemasInTableDefinitions(),
                    metadata.supportsTransactions(),
                    metadata.supportsBatchUpdates(),
                    metadata.supportsSavepoints(),
                    metadata.supportsGetGeneratedKeys(),
                    metadata.supportsMultipleResultSets(),
                    metadata.getDefaultTransactionIsolation(),
                    metadata.getMaxTableNameLength(),
                    metadata.getMaxColumnNameLength(),
                    metadata.getMaxColumnsInTable());
        } catch (SQLException e) {
            throw new IllegalStateException("JDBC capability discovery failed for source " + sourceId, e);
        }
    }
}
