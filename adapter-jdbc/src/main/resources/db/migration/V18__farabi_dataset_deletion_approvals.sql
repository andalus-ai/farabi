ALTER TABLE farabi_dataset_deletions ADD COLUMN approval_id UUID;

CREATE TABLE farabi_dataset_deletion_approvals (
    id UUID PRIMARY KEY,
    workspace_id VARCHAR(200) NOT NULL,
    dataset_id UUID NOT NULL,
    requester_id VARCHAR(300) NOT NULL,
    request_reason TEXT NOT NULL CHECK (length(trim(request_reason)) > 0),
    requested_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','APPROVED','REJECTED','EXPIRED')),
    approver_id VARCHAR(300),
    decision_reason TEXT,
    decided_at TIMESTAMPTZ,
    deletion_job_id UUID,
    UNIQUE (id, workspace_id),
    FOREIGN KEY (dataset_id, workspace_id) REFERENCES farabi_datasets(id, workspace_id),
    FOREIGN KEY (deletion_job_id) REFERENCES farabi_dataset_deletions(id),
    CHECK (expires_at > requested_at),
    CHECK ((status = 'PENDING' AND approver_id IS NULL AND decision_reason IS NULL AND decided_at IS NULL AND deletion_job_id IS NULL)
        OR (status = 'APPROVED' AND approver_id IS NOT NULL AND decision_reason IS NOT NULL AND decided_at IS NOT NULL AND deletion_job_id IS NOT NULL)
        OR (status IN ('REJECTED','EXPIRED') AND approver_id IS NOT NULL AND decision_reason IS NOT NULL AND decided_at IS NOT NULL AND deletion_job_id IS NULL))
);
CREATE INDEX idx_farabi_deletion_approval_pending
    ON farabi_dataset_deletion_approvals(workspace_id,status,expires_at,requested_at);
CREATE UNIQUE INDEX uq_farabi_deletion_approval_job
    ON farabi_dataset_deletion_approvals(deletion_job_id) WHERE deletion_job_id IS NOT NULL;

ALTER TABLE farabi_dataset_deletions
    ADD CONSTRAINT fk_farabi_deletion_approval
    FOREIGN KEY (approval_id) REFERENCES farabi_dataset_deletion_approvals(id);

CREATE TABLE farabi_dataset_deletion_approval_audit (
    id UUID PRIMARY KEY,
    approval_id UUID NOT NULL,
    workspace_id VARCHAR(200) NOT NULL,
    dataset_id UUID NOT NULL,
    event_type VARCHAR(20) NOT NULL CHECK (event_type IN ('REQUESTED','APPROVED','REJECTED','EXPIRED')),
    actor_id VARCHAR(300) NOT NULL,
    reason TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (approval_id,workspace_id) REFERENCES farabi_dataset_deletion_approvals(id,workspace_id),
    FOREIGN KEY (dataset_id,workspace_id) REFERENCES farabi_datasets(id,workspace_id)
);
CREATE INDEX idx_farabi_deletion_approval_audit
    ON farabi_dataset_deletion_approval_audit(workspace_id,occurred_at DESC);
CREATE FUNCTION farabi_reject_deletion_approval_audit_mutation() RETURNS trigger AS $$
BEGIN RAISE EXCEPTION 'deletion approval audit is append-only'; END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER farabi_dataset_deletion_approval_audit_immutable
    BEFORE UPDATE OR DELETE ON farabi_dataset_deletion_approval_audit
    FOR EACH ROW EXECUTE FUNCTION farabi_reject_deletion_approval_audit_mutation();
