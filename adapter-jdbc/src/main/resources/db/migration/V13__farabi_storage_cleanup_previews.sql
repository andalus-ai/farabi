ALTER TABLE farabi_storage_inventory_reports ADD CONSTRAINT uq_farabi_storage_inventory_report_workspace UNIQUE (id, workspace_id);

CREATE TABLE farabi_storage_cleanup_plans (
    id UUID PRIMARY KEY,
    workspace_id VARCHAR(200) NOT NULL,
    source_report_id UUID NOT NULL,
    created_by VARCHAR(300) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    candidate_count INTEGER NOT NULL CHECK (candidate_count >= 0),
    UNIQUE (id, workspace_id),
    FOREIGN KEY (workspace_id) REFERENCES farabi_workspace_storage(workspace_id),
    FOREIGN KEY (source_report_id, workspace_id) REFERENCES farabi_storage_inventory_reports(id, workspace_id),
    CHECK (expires_at > created_at)
);
CREATE INDEX idx_farabi_storage_cleanup_plans_workspace
    ON farabi_storage_cleanup_plans(workspace_id, created_at DESC);
CREATE TABLE farabi_storage_cleanup_plan_items (
    plan_id UUID NOT NULL,
    workspace_id VARCHAR(200) NOT NULL,
    item_index INTEGER NOT NULL CHECK (item_index >= 0),
    location_ref VARCHAR(2048) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes >= 0),
    last_modified TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (plan_id, item_index),
    UNIQUE (plan_id, location_ref),
    FOREIGN KEY (plan_id, workspace_id) REFERENCES farabi_storage_cleanup_plans(id, workspace_id) ON DELETE CASCADE
);
CREATE INDEX idx_farabi_storage_cleanup_plan_items_workspace
    ON farabi_storage_cleanup_plan_items(workspace_id, plan_id);

-- Preview records are evidence of what an operator reviewed; retain append-only semantics.
CREATE FUNCTION farabi_reject_storage_cleanup_preview_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'farabi storage cleanup previews are append-only';
END;
$$;
CREATE TRIGGER trg_farabi_storage_cleanup_plans_immutable
    BEFORE UPDATE OR DELETE ON farabi_storage_cleanup_plans
    FOR EACH ROW EXECUTE FUNCTION farabi_reject_storage_cleanup_preview_mutation();
CREATE TRIGGER trg_farabi_storage_cleanup_items_immutable
    BEFORE UPDATE OR DELETE ON farabi_storage_cleanup_plan_items
    FOR EACH ROW EXECUTE FUNCTION farabi_reject_storage_cleanup_preview_mutation();
