CREATE TABLE farabi_dataset_import_jobs (
    id UUID PRIMARY KEY,
    workspace_id VARCHAR(200) NOT NULL,
    dataset_id UUID NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    max_attempts INTEGER NOT NULL CHECK (max_attempts > 0),
    lease_owner VARCHAR(200),
    lease_token UUID,
    lease_until TIMESTAMPTZ,\n    available_at TIMESTAMPTZ NOT NULL,
    error_code VARCHAR(100),
    error_message VARCHAR(2000),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CHECK ((status = 'RUNNING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'RUNNING' AND lease_token IS NULL AND lease_until IS NULL)),
    UNIQUE (workspace_id, idempotency_key),
    FOREIGN KEY (dataset_id) REFERENCES farabi_datasets(id) ON DELETE CASCADE
);

CREATE INDEX idx_farabi_import_jobs_claim
    ON farabi_dataset_import_jobs(status, lease_until, created_at);
CREATE INDEX idx_farabi_import_jobs_dataset
    ON farabi_dataset_import_jobs(workspace_id, dataset_id, created_at DESC);
