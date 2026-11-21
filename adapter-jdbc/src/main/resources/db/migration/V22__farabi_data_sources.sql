-- Workspace-scoped external data-source definitions. Secrets are references only.
CREATE TABLE farabi_data_sources (
    source_id VARCHAR(63) NOT NULL,
    workspace_id VARCHAR(128) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    provider_kind VARCHAR(32) NOT NULL,
    provider_key VARCHAR(128) NOT NULL,
    endpoint TEXT NOT NULL,
    catalog_name VARCHAR(255),
    schema_name VARCHAR(255),
    credential_ref VARCHAR(1024),
    read_only BOOLEAN NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (workspace_id, source_id)
);

CREATE TABLE farabi_data_source_properties (
    source_id VARCHAR(63) NOT NULL,
    workspace_id VARCHAR(128) NOT NULL,
    property_key VARCHAR(255) NOT NULL,
    property_value TEXT NOT NULL,
    PRIMARY KEY (workspace_id, source_id, property_key),
    CONSTRAINT fk_farabi_data_source_properties
        FOREIGN KEY (workspace_id, source_id)
        REFERENCES farabi_data_sources (workspace_id, source_id)
        ON DELETE CASCADE
);

CREATE INDEX idx_farabi_data_sources_workspace
    ON farabi_data_sources (workspace_id, source_id);
