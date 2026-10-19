CREATE TABLE bi_dashboard_heads (
  workspace_id VARCHAR(128) NOT NULL,
  dashboard_id UUID NOT NULL,
  current_version INTEGER NOT NULL CHECK (current_version >= 1),
  PRIMARY KEY (workspace_id, dashboard_id)
);

CREATE TABLE bi_dashboard_versions (
  workspace_id VARCHAR(128) NOT NULL,
  dashboard_id UUID NOT NULL,
  version INTEGER NOT NULL CHECK (version >= 1),
  artifact_json JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (workspace_id, dashboard_id, version),
  FOREIGN KEY (workspace_id, dashboard_id)
    REFERENCES bi_dashboard_heads(workspace_id, dashboard_id) ON DELETE CASCADE
);

CREATE INDEX idx_bi_dashboard_versions_created
  ON bi_dashboard_versions(workspace_id, created_at DESC);
