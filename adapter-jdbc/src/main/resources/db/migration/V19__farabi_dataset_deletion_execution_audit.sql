CREATE TABLE farabi_dataset_deletion_execution_audit (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL,
    workspace_id VARCHAR(200) NOT NULL,
    dataset_id UUID NOT NULL,
    event_type VARCHAR(32) NOT NULL CHECK (event_type IN
        ('CLAIMED','RETRY_SCHEDULED','FAILED','LEASE_EXHAUSTED','SUCCEEDED')),
    attempt INTEGER NOT NULL CHECK (attempt >= 0),
    worker_id VARCHAR(300),
    lease_token UUID,
    error_code VARCHAR(100),
    occurred_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (job_id,workspace_id) REFERENCES farabi_dataset_deletions(id,workspace_id),
    FOREIGN KEY (dataset_id,workspace_id) REFERENCES farabi_datasets(id,workspace_id),
    UNIQUE (job_id,event_type,attempt)
);
CREATE INDEX idx_farabi_deletion_execution_audit
    ON farabi_dataset_deletion_execution_audit(workspace_id,job_id,occurred_at DESC);
CREATE FUNCTION farabi_reject_deletion_execution_audit_mutation() RETURNS trigger AS $$
BEGIN RAISE EXCEPTION 'deletion execution audit is append-only'; END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER farabi_dataset_deletion_execution_audit_immutable
    BEFORE UPDATE OR DELETE ON farabi_dataset_deletion_execution_audit
    FOR EACH ROW EXECUTE FUNCTION farabi_reject_deletion_execution_audit_mutation();
