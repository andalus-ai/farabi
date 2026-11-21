CREATE TABLE farabi_storage_inventory_audit (
    id UUID PRIMARY KEY,
    workspace_id VARCHAR(200) NOT NULL,
    job_id UUID NOT NULL,
    actor_id VARCHAR(300) NOT NULL,
    event_type VARCHAR(30) NOT NULL CHECK (event_type IN ('SUBMITTED','SUCCEEDED','RETRY_SCHEDULED','FAILED')),
    attempt INTEGER NOT NULL CHECK (attempt >= 0),
    report_id UUID,
    error_code VARCHAR(100),
    occurred_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (workspace_id) REFERENCES farabi_workspace_storage(workspace_id),
    FOREIGN KEY (job_id) REFERENCES farabi_storage_inventory_jobs(id),
    FOREIGN KEY (report_id) REFERENCES farabi_storage_inventory_reports(id),
    UNIQUE (job_id, event_type, attempt)
);
CREATE INDEX idx_farabi_storage_inventory_audit_workspace
    ON farabi_storage_inventory_audit(workspace_id, occurred_at DESC);
CREATE INDEX idx_farabi_storage_inventory_audit_job
    ON farabi_storage_inventory_audit(job_id, occurred_at);

-- Enforce append-only semantics even if an application path accidentally issues UPDATE/DELETE.
CREATE FUNCTION farabi_reject_storage_inventory_audit_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'farabi_storage_inventory_audit is append-only';
END;
$$;
CREATE TRIGGER trg_farabi_storage_inventory_audit_immutable
    BEFORE UPDATE OR DELETE ON farabi_storage_inventory_audit
    FOR EACH ROW EXECUTE FUNCTION farabi_reject_storage_inventory_audit_mutation();
