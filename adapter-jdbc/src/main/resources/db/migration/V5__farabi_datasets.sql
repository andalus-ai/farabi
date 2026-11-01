CREATE TABLE farabi_datasets (
    id UUID PRIMARY KEY,
    workspace_id VARCHAR(200) NOT NULL,
    name VARCHAR(300) NOT NULL,
    source_id VARCHAR(200) NOT NULL,
    format VARCHAR(40) NOT NULL,
    location_ref TEXT NOT NULL,
    lifecycle VARCHAR(40) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CHECK (updated_at >= created_at)
);

CREATE UNIQUE INDEX uq_farabi_dataset_workspace_name
    ON farabi_datasets(workspace_id, LOWER(name));
CREATE INDEX idx_farabi_datasets_workspace_updated
    ON farabi_datasets(workspace_id, updated_at DESC);

CREATE TABLE farabi_dataset_profiles (
    dataset_id UUID NOT NULL,
    workspace_id VARCHAR(200) NOT NULL,
    row_count BIGINT NOT NULL CHECK (row_count >= 0),
    profiled_at TIMESTAMPTZ NOT NULL,
    profiler_version VARCHAR(100) NOT NULL,
    PRIMARY KEY (dataset_id, workspace_id),
    FOREIGN KEY (dataset_id) REFERENCES farabi_datasets(id) ON DELETE CASCADE
);

CREATE TABLE farabi_dataset_profile_columns (
    dataset_id UUID NOT NULL,
    workspace_id VARCHAR(200) NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    name VARCHAR(300) NOT NULL,
    logical_type VARCHAR(40) NOT NULL,
    nullable BOOLEAN NOT NULL,
    null_count BIGINT NOT NULL CHECK (null_count >= 0),
    distinct_count BIGINT NOT NULL CHECK (distinct_count >= 0),
    PRIMARY KEY (dataset_id, workspace_id, ordinal),
    UNIQUE (dataset_id, workspace_id, name),
    FOREIGN KEY (dataset_id, workspace_id)
        REFERENCES farabi_dataset_profiles(dataset_id, workspace_id) ON DELETE CASCADE
);
