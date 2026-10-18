package tech.kayys.andalus.bi.connector;

import java.util.Objects;

/**
 * A source references server-side credentials by opaque key only.
 * Never persist credentials, passwords or connection strings here.
 */
public record SourceDescriptor(String id, String connectorType, String secretRef, String workspaceId) {
    public SourceDescriptor {
        id = required("id", id);
        connectorType = required("connectorType", connectorType);
        secretRef = required("secretRef", secretRef);
        workspaceId = required("workspaceId", workspaceId);
    }

    private static String required(String key, String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " required");
        return value.trim();
    }
}
