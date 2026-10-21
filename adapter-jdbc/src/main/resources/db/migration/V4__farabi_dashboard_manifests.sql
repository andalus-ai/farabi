CREATE TABLE farabi_dashboard_manifests (
 id UUID PRIMARY KEY,
 draft_id UUID NOT NULL,
 workspace_id VARCHAR(200) NOT NULL,
 revision INTEGER NOT NULL CHECK(revision>0),
 schema_version INTEGER NOT NULL CHECK(schema_version=1),
 spec_json JSONB NOT NULL,
 prompt_provenance TEXT,
 published_by VARCHAR(200) NOT NULL,
 published_at TIMESTAMPTZ NOT NULL,
 UNIQUE(workspace_id,draft_id,revision)
);
CREATE INDEX idx_farabi_manifest_workspace ON farabi_dashboard_manifests(workspace_id,published_at DESC);
