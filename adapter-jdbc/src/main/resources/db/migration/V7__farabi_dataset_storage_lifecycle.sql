ALTER TABLE farabi_datasets
    ADD COLUMN deletion_requested_at TIMESTAMPTZ,
    ADD COLUMN deleted_at TIMESTAMPTZ;

CREATE TABLE farabi_dataset_deletions (
    id UUID PRIMARY KEY,
    workspace_id VARCHAR(200) NOT NULL,
    dataset_id UUID NOT NULL UNIQUE,
    location_ref TEXT NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    max_attempts INTEGER NOT NULL CHECK (max_attempts > 0),
    lease_owner VARCHAR(200),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    available_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CHECK ((status = 'RUNNING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'RUNNING' AND lease_token IS NULL AND lease_until IS NULL)),
    FOREIGN KEY (dataset_id) REFERENCES farabi_datasets(id)
);
CREATE INDEX idx_farabi_dataset_deletions_claim
    ON farabi_dataset_deletions(status, available_at, lease_until, created_at);
