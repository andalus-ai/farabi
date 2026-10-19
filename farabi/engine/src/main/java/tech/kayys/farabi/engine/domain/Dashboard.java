package tech.kayys.farabi.engine.domain;

import java.util.Objects;
import java.util.UUID;

public record Dashboard(UUID id, String workspaceId, String title) {
    public Dashboard {
        Objects.requireNonNull(id, "id");
        if (workspaceId == null || workspaceId.isBlank()) throw new IllegalArgumentException("workspaceId required");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title required");
    }
}
