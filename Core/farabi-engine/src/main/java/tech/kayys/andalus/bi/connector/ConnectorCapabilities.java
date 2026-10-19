package tech.kayys.andalus.bi.connector;

/** Declared connector features; callers must not assume all sources support them. */
public record ConnectorCapabilities(
        boolean preparedParameters,
        boolean queryTimeout,
        boolean schemaDiscovery,
        boolean cancellation,
        int maxResultRows) {
    public ConnectorCapabilities {
        if (maxResultRows < 1) throw new IllegalArgumentException("maxResultRows must be positive");
    }
}
