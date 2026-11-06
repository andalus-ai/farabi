CREATE TABLE farabi_storage_inventory_reports (
    id UUID PRIMARY KEY,
    workspace_id VARCHAR(200) NOT NULL,
    inspected_at TIMESTAMPTZ NOT NULL,
    orphan_grace_seconds BIGINT NOT NULL CHECK (orphan_grace_seconds >= 0),
    catalog_objects INTEGER NOT NULL CHECK (catalog_objects >= 0),
    provider_objects INTEGER NOT NULL CHECK (provider_objects >= 0),
    finding_count INTEGER NOT NULL CHECK (finding_count >= 0),
    FOREIGN KEY (workspace_id) REFERENCES farabi_workspace_storage(workspace_id)
);

CREATE INDEX idx_farabi_storage_inventory_workspace
    ON farabi_storage_inventory_reports(workspace_id, inspected_at DESC);

CREATE TABLE farabi_storage_inventory_findings (
    report_id UUID NOT NULL,
    workspace_id VARCHAR(200) NOT NULL,
    finding_index INTEGER NOT NULL CHECK (finding_index >= 0),
    finding_type VARCHAR(40) NOT NULL,
    location_ref VARCHAR(2048) NOT NULL,
    expected_bytes BIGINT NOT NULL CHECK (expected_bytes >= 0),
    actual_bytes BIGINT NOT NULL CHECK (actual_bytes >= 0),
    object_last_modified TIMESTAMPTZ,
    PRIMARY KEY (report_id, finding_index),
    FOREIGN KEY (report_id) REFERENCES farabi_storage_inventory_reports(id) ON DELETE CASCADE,
    FOREIGN KEY (workspace_id) REFERENCES farabi_workspace_storage(workspace_id)
);

CREATE INDEX idx_farabi_storage_inventory_findings_workspace
    ON farabi_storage_inventory_findings(workspace_id, report_id);
