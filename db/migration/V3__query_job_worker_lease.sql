-- Requires V2__query_jobs.sql from the query-job lifecycle milestone.
ALTER TABLE bi_query_jobs
  ADD COLUMN lease_owner VARCHAR(128),
  ADD COLUMN lease_until TIMESTAMPTZ,
  ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0);
CREATE INDEX idx_bi_query_jobs_recovery
  ON bi_query_jobs(status, lease_until, updated_at)
  WHERE status IN ('PENDING','SUBMITTED','RUNNING');

CREATE TABLE bi_query_job_audit (
  event_id UUID PRIMARY KEY,
  job_id UUID NOT NULL REFERENCES bi_query_jobs(id),
  workspace_id VARCHAR(128) NOT NULL,
  subject_id VARCHAR(256) NOT NULL,
  event_type VARCHAR(64) NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_bi_query_job_audit_job ON bi_query_job_audit(job_id, occurred_at);
