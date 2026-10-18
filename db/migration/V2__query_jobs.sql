CREATE TABLE bi_query_jobs (
    id UUID PRIMARY KEY,
    workspace_id VARCHAR(128) NOT NULL,
    subject_id VARCHAR(256) NOT NULL,
    source_id VARCHAR(128) NOT NULL,
    provider_job_id VARCHAR(128),
    status VARCHAR(24) NOT NULL CHECK (status IN
      ('PENDING','SUBMITTED','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CHECK (expires_at > created_at)
);
CREATE INDEX idx_bi_query_jobs_owner ON bi_query_jobs(workspace_id,subject_id,created_at DESC);
CREATE UNIQUE INDEX idx_bi_query_jobs_provider ON bi_query_jobs(workspace_id,source_id,provider_job_id)
    WHERE provider_job_id IS NOT NULL;
