CREATE TABLE farabi_storage_quota_repair_applications (
    id UUID PRIMARY KEY,
    preview_id UUID NOT NULL UNIQUE,
    workspace_id VARCHAR(200) NOT NULL,
    approved_by VARCHAR(300) NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL,
    previous_used_bytes BIGINT NOT NULL CHECK (previous_used_bytes >= 0),
    applied_used_bytes BIGINT NOT NULL CHECK (applied_used_bytes >= 0),
    previous_reserved_bytes BIGINT NOT NULL CHECK (previous_reserved_bytes >= 0),
    applied_reserved_bytes BIGINT NOT NULL CHECK (applied_reserved_bytes >= 0),
    FOREIGN KEY (preview_id, workspace_id)
        REFERENCES farabi_storage_quota_repair_previews(id, workspace_id)
);
CREATE INDEX idx_farabi_quota_repair_applications_workspace
    ON farabi_storage_quota_repair_applications(workspace_id, applied_at DESC);
CREATE FUNCTION farabi_reject_quota_repair_application_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'quota repair application audit is append-only';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER farabi_storage_quota_repair_applications_immutable
    BEFORE UPDATE OR DELETE ON farabi_storage_quota_repair_applications
    FOR EACH ROW EXECUTE FUNCTION farabi_reject_quota_repair_application_mutation();
