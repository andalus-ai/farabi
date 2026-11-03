ALTER TABLE farabi_dataset_deletion_execution_audit
    DROP CONSTRAINT farabi_dataset_deletion_execution_audit_event_type_check;

ALTER TABLE farabi_dataset_deletion_execution_audit
    ADD CONSTRAINT farabi_dataset_deletion_execution_audit_event_type_check
    CHECK (event_type IN ('CLAIMED','LEASE_EXPIRED','RETRY_SCHEDULED','FAILED',
        'LEASE_EXHAUSTED','SUCCEEDED','RECOVERY_QUEUED','OBJECT_ABSENT_VERIFIED'));
