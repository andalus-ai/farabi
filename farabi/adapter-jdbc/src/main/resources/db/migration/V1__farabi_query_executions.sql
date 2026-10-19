CREATE TABLE IF NOT EXISTS farabi_query_executions (
    id UUID PRIMARY KEY,
    workspace_id VARCHAR(200) NOT NULL,
    subject_id VARCHAR(200) NOT NULL,
    source_id VARCHAR(200) NOT NULL,
    provider_job_id VARCHAR(500),
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','SUBMITTED','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CHECK (updated_at >= created_at)
);
CREATE INDEX IF NOT EXISTS idx_farabi_query_executions_workspace_updated
    ON farabi_query_executions (workspace_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_farabi_query_executions_reconcile
    ON farabi_query_executions (status, updated_at)
    WHERE status IN ('PENDING','SUBMITTED','RUNNING');
