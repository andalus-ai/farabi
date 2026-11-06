package tech.kayys.farabi.engine;

import java.util.Objects;

/** Immutable, advisory capabilities reported by a connected data source. */
public record DataSourceCapabilities(
        String productName,
        String productVersion,
        String driverName,
        String driverVersion,
        String identifierQuoteString,
        boolean supportsCatalogs,
        boolean supportsSchemas,
        boolean supportsTransactions,
        boolean supportsBatchUpdates,
        boolean supportsSavepoints,
        boolean supportsGeneratedKeys,
        boolean supportsMultipleResultSets,
        int defaultTransactionIsolation,
        int maxTableNameLength,
        int maxColumnNameLength,
        int maxColumnsInTable) {
    public DataSourceCapabilities {
        if (productName == null || productName.isBlank()) {
            throw new IllegalArgumentException("productName required");
        }
        Objects.requireNonNull(productVersion, "productVersion");
        Objects.requireNonNull(driverName, "driverName");
        Objects.requireNonNull(driverVersion, "driverVersion");
        Objects.requireNonNull(identifierQuoteString, "identifierQuoteString");
        if (maxTableNameLength < 0 || maxColumnNameLength < 0 || maxColumnsInTable < 0) {
            throw new IllegalArgumentException("metadata limits cannot be negative");
        }
    }
}
