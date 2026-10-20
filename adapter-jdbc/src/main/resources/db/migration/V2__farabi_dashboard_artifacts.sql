CREATE TABLE farabi_dashboard_artifacts (
    id UUID PRIMARY KEY,
    draft_id UUID NOT NULL,
    workspace_id VARCHAR(200) NOT NULL,
    title VARCHAR(500) NOT NULL,
    revision INTEGER NOT NULL CHECK (revision > 0),
    charts TEXT NOT NULL,
    prompt_provenance TEXT,
    published_by VARCHAR(200) NOT NULL,
    published_at TIMESTAMPTZ NOT NULL,
    UNIQUE (draft_id, revision)
);
CREATE INDEX idx_farabi_dashboard_artifacts_workspace ON farabi_dashboard_artifacts(workspace_id, published_at DESC);
