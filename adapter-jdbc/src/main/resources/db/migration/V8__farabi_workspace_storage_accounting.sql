ALTER TABLE farabi_datasets
    ADD COLUMN storage_bytes BIGINT NOT NULL DEFAULT 0 CHECK (storage_bytes >= 0);

CREATE TABLE farabi_workspace_storage (
    workspace_id VARCHAR(200) PRIMARY KEY,
    quota_bytes BIGINT NOT NULL CHECK (quota_bytes >= 0),
    used_bytes BIGINT NOT NULL DEFAULT 0 CHECK (used_bytes >= 0),
    reserved_bytes BIGINT NOT NULL DEFAULT 0 CHECK (reserved_bytes >= 0),
    updated_at TIMESTAMPTZ NOT NULL,
    CHECK (used_bytes + reserved_bytes <= quota_bytes)
);

CREATE TABLE farabi_storage_reservations (
    id UUID PRIMARY KEY,
    workspace_id VARCHAR(200) NOT NULL,
    reserved_bytes BIGINT NOT NULL CHECK (reserved_bytes > 0),
    dataset_id UUID UNIQUE,
    status VARCHAR(20) NOT NULL CHECK (status IN ('RESERVED','COMMITTED','RELEASED')),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (workspace_id) REFERENCES farabi_workspace_storage(workspace_id),
    FOREIGN KEY (dataset_id) REFERENCES farabi_datasets(id)
);

CREATE INDEX idx_farabi_storage_reservations_workspace
    ON farabi_storage_reservations(workspace_id,status,created_at);
