package tech.kayys.andalus.bi.connector;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable, workspace-scoped source registry.
 * The registry must be populated from a trusted source administration service.
 */
public final class SourceRegistry {
    private final Map<String, SourceDescriptor> sources;

    public SourceRegistry(Collection<SourceDescriptor> entries) {
        Objects.requireNonNull(entries);
        Map<String, SourceDescriptor> map = new HashMap<>();
        for (SourceDescriptor source : entries) {
            Objects.requireNonNull(source);
            if (map.putIfAbsent(key(source.workspaceId(), source.id()), source) != null) {
                throw new IllegalArgumentException("duplicate source in workspace: " + source.id());
            }
        }
        sources = Map.copyOf(map);
    }

    public SourceDescriptor require(String workspaceId, String sourceId) {
        SourceDescriptor source = sources.get(key(workspaceId, sourceId));
        if (source == null) throw new IllegalArgumentException("source not found");
        return source;
    }

    private static String key(String workspaceId, String sourceId) {
        if (workspaceId == null || workspaceId.isBlank() || sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("workspaceId and sourceId required");
        }
        return workspaceId + "\u0000" + sourceId;
    }
}
