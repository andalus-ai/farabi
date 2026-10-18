package tech.kayys.andalus.bi.security;

import java.util.Objects;
import java.util.Set;

/** Trusted, authenticated identity; never construct from request JSON or headers directly. */
public record AccessContext(String subjectId, Set<String> workspaceIds) {
    public AccessContext {
        if (subjectId == null || subjectId.isBlank()) throw new IllegalArgumentException("subjectId required");
        workspaceIds = Set.copyOf(Objects.requireNonNull(workspaceIds, "workspaceIds"));
    }

    public void requireWorkspace(String workspaceId) {
        if (workspaceId == null || !workspaceIds.contains(workspaceId)) {
            throw new AccessDeniedException("workspace access denied");
        }
    }
}
