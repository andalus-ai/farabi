CREATE TABLE farabi_dataset_deletion_reconciliation_reports (
    id UUID PRIMARY KEY,
    workspace_id VARCHAR(200) NOT NULL,
    inspected_at TIMESTAMPTZ NOT NULL,
    datasets_inspected INTEGER NOT NULL CHECK (datasets_inspected >= 0),
    deletion_jobs_inspected INTEGER NOT NULL CHECK (deletion_jobs_inspected >= 0),
    finding_count INTEGER NOT NULL CHECK (finding_count >= 0),
    consistent BOOLEAN NOT NULL,
    UNIQUE (id,workspace_id),
    CHECK (consistent = (finding_count = 0))
);
CREATE INDEX idx_farabi_deletion_reconciliation_recent
    ON farabi_dataset_deletion_reconciliation_reports(workspace_id,inspected_at DESC);

CREATE TABLE farabi_dataset_deletion_reconciliation_findings (
    id UUID PRIMARY KEY,
    report_id UUID NOT NULL,
    workspace_id VARCHAR(200) NOT NULL,
    finding_type VARCHAR(64) NOT NULL CHECK (finding_type IN (
        'ARCHIVED_OBJECT_PRESENT','SUCCEEDED_DATASET_NOT_ARCHIVED',
        'ARCHIVED_STORAGE_BYTES_NONZERO','LEDGER_USED_MISMATCH','LEDGER_RESERVED_MISMATCH')),
    dataset_id UUID,
    deletion_job_id UUID,
    location_ref TEXT,
    expected_bytes BIGINT NOT NULL CHECK (expected_bytes >= 0),
    actual_bytes BIGINT NOT NULL CHECK (actual_bytes >= 0),
    FOREIGN KEY (report_id,workspace_id)
        REFERENCES farabi_dataset_deletion_reconciliation_reports(id,workspace_id),
    FOREIGN KEY (dataset_id,workspace_id)
        REFERENCES farabi_datasets(id,workspace_id),
    FOREIGN KEY (deletion_job_id,workspace_id)
        REFERENCES farabi_dataset_deletions(id,workspace_id)
);
CREATE INDEX idx_farabi_deletion_reconciliation_findings
    ON farabi_dataset_deletion_reconciliation_findings(report_id,workspace_id);

CREATE FUNCTION farabi_reject_deletion_reconciliation_mutation() RETURNS trigger AS $$
BEGIN RAISE EXCEPTION 'deletion reconciliation reports are append-only'; END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER farabi_deletion_reconciliation_reports_immutable
    BEFORE UPDATE OR DELETE ON farabi_dataset_deletion_reconciliation_reports
    FOR EACH ROW EXECUTE FUNCTION farabi_reject_deletion_reconciliation_mutation();
CREATE TRIGGER farabi_deletion_reconciliation_findings_immutable
    BEFORE UPDATE OR DELETE ON farabi_dataset_deletion_reconciliation_findings
    FOR EACH ROW EXECUTE FUNCTION farabi_reject_deletion_reconciliation_mutation();
