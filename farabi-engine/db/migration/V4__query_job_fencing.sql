-- Depends on V2 query job schema and V3 worker lease columns.
ALTER TABLE bi_query_jobs
  ADD COLUMN lease_epoch BIGINT NOT NULL DEFAULT 0
    CHECK (lease_epoch >= 0);
CREATE INDEX idx_bi_query_jobs_active_lease
  ON bi_query_jobs(id, lease_owner, lease_epoch, lease_until);
