ALTER TABLE farabi_storage_reconciliation_reports
    ADD CONSTRAINT uq_farabi_storage_reconciliation_id_workspace UNIQUE (id, workspace_id);

CREATE TABLE farabi_storage_quota_repair_previews (
    id UUID PRIMARY KEY,
    workspace_id VARCHAR(200) NOT NULL,
    created_by VARCHAR(300) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    reconciliation_report_id UUID NOT NULL,
    quota_bytes BIGINT NOT NULL CHECK (quota_bytes >= 0),
    ledger_used_bytes BIGINT NOT NULL CHECK (ledger_used_bytes >= 0),
    proposed_used_bytes BIGINT NOT NULL CHECK (proposed_used_bytes >= 0),
    used_delta_bytes BIGINT NOT NULL,
    ledger_reserved_bytes BIGINT NOT NULL CHECK (ledger_reserved_bytes >= 0),
    proposed_reserved_bytes BIGINT NOT NULL CHECK (proposed_reserved_bytes >= 0),
    reserved_delta_bytes BIGINT NOT NULL,
    committed_reservation_bytes BIGINT NOT NULL CHECK (committed_reservation_bytes >= 0),
    applicable BOOLEAN NOT NULL,
    FOREIGN KEY (reconciliation_report_id, workspace_id)
        REFERENCES farabi_storage_reconciliation_reports(id, workspace_id)
);
CREATE INDEX idx_farabi_quota_repair_previews_workspace
    ON farabi_storage_quota_repair_previews(workspace_id, created_at DESC);
CREATE FUNCTION farabi_reject_quota_repair_preview_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'quota repair previews are append-only';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER farabi_storage_quota_repair_previews_immutable
    BEFORE UPDATE OR DELETE ON farabi_storage_quota_repair_previews
    FOR EACH ROW EXECUTE FUNCTION farabi_reject_quota_repair_preview_mutation();
ALTER TABLE farabi_storage_quota_repair_previews
    ADD CONSTRAINT uq_farabi_quota_repair_preview_id_workspace UNIQUE (id, workspace_id);
