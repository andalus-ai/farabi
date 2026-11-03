package tech.kayys.farabi.engine;

/** Dedicated permission boundary for retrying exhausted destructive dataset operations. */
@FunctionalInterface
public interface DatasetDeletionRecoveryAuthorizer {
    void authorizeRecovery(String subjectId, String workspaceId);
}
