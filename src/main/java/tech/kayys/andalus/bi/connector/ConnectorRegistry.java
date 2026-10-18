package tech.kayys.andalus.bi.connector;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable connector registry, rejecting duplicate connector types. */
public final class ConnectorRegistry {
    private final Map<String, SourceConnector> connectors;

    public ConnectorRegistry(Collection<SourceConnector> entries) {
        Objects.requireNonNull(entries, "entries");
        Map<String, SourceConnector> map = new HashMap<>();
        for (SourceConnector connector : entries) {
            Objects.requireNonNull(connector, "connector");
            String type = Objects.requireNonNull(connector.type(), "connector type");
            if (type.isBlank() || map.putIfAbsent(type, connector) != null) {
                throw new IllegalArgumentException("invalid or duplicate connector type: " + type);
            }
        }
        connectors = Map.copyOf(map);
    }

    public SourceConnector require(String type) {
        SourceConnector connector = connectors.get(type);
        if (connector == null) throw new IllegalArgumentException("unsupported connector: " + type);
        return connector;
    }
}
