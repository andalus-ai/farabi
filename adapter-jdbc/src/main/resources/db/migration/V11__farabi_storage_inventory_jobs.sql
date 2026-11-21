CREATE TABLE farabi_storage_inventory_jobs (
    id UUID PRIMARY KEY,
    workspace_id VARCHAR(200) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
    attempts INTEGER NOT NULL CHECK (attempts >= 0),
    max_attempts INTEGER NOT NULL CHECK (max_attempts > 0),
    lease_owner VARCHAR(200),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    available_at TIMESTAMPTZ NOT NULL,
    report_id UUID,
    error_code VARCHAR(100),
    error_message VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (workspace_id, idempotency_key),
    FOREIGN KEY (workspace_id) REFERENCES farabi_workspace_storage(workspace_id),
    FOREIGN KEY (report_id) REFERENCES farabi_storage_inventory_reports(id),
    CHECK ((status = 'RUNNING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'RUNNING' AND lease_token IS NULL AND lease_until IS NULL))
);
CREATE INDEX idx_farabi_storage_inventory_jobs_claim
    ON farabi_storage_inventory_jobs(status, available_at, created_at);
CREATE INDEX idx_farabi_storage_inventory_jobs_workspace
    ON farabi_storage_inventory_jobs(workspace_id, created_at DESC);
