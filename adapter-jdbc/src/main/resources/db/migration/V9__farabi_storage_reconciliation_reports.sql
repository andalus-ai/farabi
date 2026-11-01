CREATE TABLE farabi_storage_reconciliation_reports (
 id UUID PRIMARY KEY,
 workspace_id VARCHAR(200) NOT NULL,
 inspected_at TIMESTAMPTZ NOT NULL,
 quota_bytes BIGINT NOT NULL CHECK(quota_bytes>=0),
 ledger_used_bytes BIGINT NOT NULL CHECK(ledger_used_bytes>=0),
 observed_used_bytes BIGINT NOT NULL CHECK(observed_used_bytes>=0),
 ledger_reserved_bytes BIGINT NOT NULL CHECK(ledger_reserved_bytes>=0),
 observed_reserved_bytes BIGINT NOT NULL CHECK(observed_reserved_bytes>=0),
 finding_types VARCHAR(500) NOT NULL,
 FOREIGN KEY(workspace_id) REFERENCES farabi_workspace_storage(workspace_id)
);
CREATE INDEX idx_farabi_storage_reconciliation_workspace ON farabi_storage_reconciliation_reports(workspace_id,inspected_at DESC);
