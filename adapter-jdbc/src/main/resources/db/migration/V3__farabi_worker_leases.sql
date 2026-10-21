ALTER TABLE farabi_query_executions
 ADD COLUMN lease_owner VARCHAR(200),
 ADD COLUMN lease_fence BIGINT NOT NULL DEFAULT 0 CHECK(lease_fence>=0),
 ADD COLUMN lease_expires_at TIMESTAMPTZ;
CREATE INDEX idx_farabi_execution_expired_leases ON farabi_query_executions(lease_expires_at) WHERE status IN ('SUBMITTED','RUNNING');
