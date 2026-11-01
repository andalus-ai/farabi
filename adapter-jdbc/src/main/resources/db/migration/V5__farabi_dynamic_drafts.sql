-- Dynamic (agent- or editor-authored) dashboard drafts. Ephemeral by design: drafts expire and are never served as dashboards.
-- Standard SQL types only (no JSONB) so the same migration runs on PostgreSQL and in H2-based tests.
CREATE TABLE farabi_dynamic_drafts (
 id UUID PRIMARY KEY,
 lineage_id UUID NOT NULL,
 workspace_id VARCHAR(200) NOT NULL,
 owner_id VARCHAR(200) NOT NULL,
 prompt TEXT NOT NULL,
 spec_json TEXT NOT NULL,
 assumptions_json TEXT NOT NULL,
 proposer VARCHAR(100) NOT NULL,
 parent_manifest_id UUID,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
 promoted_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_farabi_drafts_owner ON farabi_dynamic_drafts(workspace_id,owner_id,created_at);
CREATE INDEX idx_farabi_drafts_expiry ON farabi_dynamic_drafts(expires_at);
