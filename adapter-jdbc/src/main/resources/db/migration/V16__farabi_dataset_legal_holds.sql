CREATE TABLE farabi_dataset_legal_holds (
    id UUID PRIMARY KEY,
    workspace_id VARCHAR(200) NOT NULL,
    dataset_id UUID NOT NULL,
    reason TEXT NOT NULL,
    placed_by VARCHAR(300) NOT NULL,
    placed_at TIMESTAMPTZ NOT NULL,
    released_by VARCHAR(300),
    released_at TIMESTAMPTZ,
    release_reason TEXT,
    FOREIGN KEY (dataset_id) REFERENCES farabi_datasets(id),
    CHECK (length(trim(reason)) > 0),
    CHECK ((released_at IS NULL AND released_by IS NULL AND release_reason IS NULL)
        OR (released_at IS NOT NULL AND released_by IS NOT NULL AND release_reason IS NOT NULL
            AND length(trim(release_reason)) > 0)),
    UNIQUE (id, workspace_id)
);
CREATE INDEX idx_farabi_dataset_legal_holds_active
    ON farabi_dataset_legal_holds(workspace_id, dataset_id)
    WHERE released_at IS NULL;

CREATE TABLE farabi_dataset_legal_hold_audit (
    id UUID PRIMARY KEY,
    hold_id UUID NOT NULL,
    workspace_id VARCHAR(200) NOT NULL,
    dataset_id UUID NOT NULL,
    event_type VARCHAR(20) NOT NULL CHECK (event_type IN ('PLACED','RELEASED')),
    actor_id VARCHAR(300) NOT NULL,
    reason TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (hold_id, workspace_id) REFERENCES farabi_dataset_legal_holds(id, workspace_id),
    FOREIGN KEY (dataset_id) REFERENCES farabi_datasets(id)
);
CREATE INDEX idx_farabi_dataset_legal_hold_audit_workspace
    ON farabi_dataset_legal_hold_audit(workspace_id, occurred_at DESC);

CREATE FUNCTION farabi_reject_legal_hold_audit_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'dataset legal hold audit is append-only';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER farabi_dataset_legal_hold_audit_immutable
    BEFORE UPDATE OR DELETE ON farabi_dataset_legal_hold_audit
    FOR EACH ROW EXECUTE FUNCTION farabi_reject_legal_hold_audit_mutation();
