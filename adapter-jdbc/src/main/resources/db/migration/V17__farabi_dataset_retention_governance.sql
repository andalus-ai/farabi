CREATE TABLE farabi_dataset_retention_policies (
 id UUID PRIMARY KEY,
 workspace_id VARCHAR(200) NOT NULL,
 source_id VARCHAR(200),
 retention_days INTEGER NOT NULL CHECK (retention_days BETWEEN 1 AND 36500),
 effective_at TIMESTAMPTZ NOT NULL,
 created_by VARCHAR(300) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL,
 supersedes_policy_id UUID REFERENCES farabi_dataset_retention_policies(id),
 UNIQUE(id, workspace_id)
);
CREATE INDEX idx_farabi_retention_policy_lookup ON farabi_dataset_retention_policies(workspace_id, source_id, effective_at DESC);

CREATE TABLE farabi_dataset_retention_exemptions (
 id UUID PRIMARY KEY,
 workspace_id VARCHAR(200) NOT NULL,
 dataset_id UUID NOT NULL REFERENCES farabi_datasets(id),
 reason TEXT NOT NULL CHECK (length(trim(reason)) > 0),
 created_by VARCHAR(300) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL,
 expires_at TIMESTAMPTZ,
 revoked_by VARCHAR(300), revoked_at TIMESTAMPTZ, revoke_reason TEXT,
 UNIQUE(id, workspace_id),
 CHECK ((revoked_at IS NULL AND revoked_by IS NULL AND revoke_reason IS NULL) OR (revoked_at IS NOT NULL AND revoked_by IS NOT NULL AND length(trim(revoke_reason)) > 0))
);
CREATE INDEX idx_farabi_retention_exemptions_active ON farabi_dataset_retention_exemptions(workspace_id,dataset_id,expires_at) WHERE revoked_at IS NULL;

CREATE TABLE farabi_dataset_retention_audit (
 id UUID PRIMARY KEY, workspace_id VARCHAR(200) NOT NULL,
 policy_id UUID, dataset_id UUID, exemption_id UUID,
 event_type VARCHAR(30) NOT NULL CHECK (event_type IN ('POLICY_CREATED','EXEMPTION_CREATED','EXEMPTION_REVOKED')),
 actor_id VARCHAR(300) NOT NULL, reason TEXT NOT NULL, occurred_at TIMESTAMPTZ NOT NULL,
 FOREIGN KEY (policy_id,workspace_id) REFERENCES farabi_dataset_retention_policies(id,workspace_id),
 FOREIGN KEY (dataset_id) REFERENCES farabi_datasets(id),
 FOREIGN KEY (exemption_id,workspace_id) REFERENCES farabi_dataset_retention_exemptions(id,workspace_id)
);
CREATE INDEX idx_farabi_retention_audit_workspace ON farabi_dataset_retention_audit(workspace_id,occurred_at DESC);
CREATE FUNCTION farabi_reject_retention_audit_mutation() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'dataset retention audit is append-only'; END; $$ LANGUAGE plpgsql;
CREATE TRIGGER farabi_dataset_retention_audit_immutable BEFORE UPDATE OR DELETE ON farabi_dataset_retention_audit FOR EACH ROW EXECUTE FUNCTION farabi_reject_retention_audit_mutation();
