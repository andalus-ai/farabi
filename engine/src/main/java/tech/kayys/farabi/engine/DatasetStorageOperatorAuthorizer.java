package tech.kayys.farabi.engine;

/** Explicit operator authorization boundary for storage reconciliation and repair workflows. */
@FunctionalInterface
public interface DatasetStorageOperatorAuthorizer {
    void authorize(String subjectId, String workspaceId);
}
