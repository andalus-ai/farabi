package tech.kayys.farabi.engine;

/** Separate permission boundary for approving/rejecting destructive dataset operations. */
@FunctionalInterface
public interface DatasetDeletionApprovalAuthorizer {
    void authorizeApproval(String subjectId, String workspaceId);
}
