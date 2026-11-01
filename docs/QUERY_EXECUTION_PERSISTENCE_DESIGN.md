# Query execution persistence: additive migration design

- **Status:** Proposed implementation detail; not an approved migration
- **Date:** 2026-11-01
- **Parent design:** ADR-009
- **Related plan:** [Query execution reliability plan](QUERY_EXECUTION_RELIABILITY_PLAN.md)

## Scope and evidence

This design is based on the current JDBC implementation:

- V1 creates the farabi_query_executions table with execution identity, workspace, subject, source, provider job ID, status, and timestamps.
- V3 already adds lease_owner, monotonically incremented lease_fence, and lease_expires_at; JdbcWorkerLeaseRepository uses those fields for claim, renew, and fenced completion.
- JdbcExecutionRepository currently inserts/selects only the V1 fields and has status-based compare-and-set transitions, but no metadata update method.
- QueryPollingWorker polls only known provider job IDs. It does not discover ambiguous submissions by idempotency key.
- V6's dataset import job table demonstrates the repository's existing convention for a workspace-scoped idempotency key and lease-based work.

This is a design proposal only. Do not add the migration or change production repository behavior until ADR-009 and its policy decisions are approved.

## Proposed additive V7 shape

The following is a candidate migration shape, not executable migration authorization:

~~~sql
ALTER TABLE farabi_query_executions
    ADD COLUMN deadline_at TIMESTAMPTZ,
    ADD COLUMN timeout_policy_version VARCHAR(100),
    ADD COLUMN idempotency_key VARCHAR(200),
    ADD COLUMN outcome_certainty VARCHAR(30),
    ADD COLUMN cancel_requested_at TIMESTAMPTZ,
    ADD COLUMN cancel_requested_by VARCHAR(200),
    ADD COLUMN last_reconciled_at TIMESTAMPTZ,
    ADD COLUMN reconciliation_attempt INTEGER NOT NULL DEFAULT 0
        CHECK (reconciliation_attempt >= 0);

ALTER TABLE farabi_query_executions
    ADD CONSTRAINT chk_farabi_execution_outcome_certainty
    CHECK (outcome_certainty IS NULL OR outcome_certainty IN
        ('NOT_SUBMITTED', 'UNKNOWN', 'CONFIRMED'));

CREATE UNIQUE INDEX uq_farabi_execution_workspace_idempotency
    ON farabi_query_executions(workspace_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX idx_farabi_execution_deadline
    ON farabi_query_executions(status, deadline_at)
    WHERE deadline_at IS NOT NULL
      AND status IN ('PENDING', 'SUBMITTED', 'RUNNING');

CREATE INDEX idx_farabi_execution_reconciliation
    ON farabi_query_executions(status, last_reconciled_at, updated_at)
    WHERE status IN ('PENDING', 'SUBMITTED', 'RUNNING');
~~~

### Field semantics

| Column | New-write rule | Legacy-row treatment |
|---|---|---|
| deadline_at | Set before first provider submission, using the approved timeout policy | NULL; do not fabricate historical deadlines |
| timeout_policy_version | Set with the deadline | NULL |
| idempotency_key | Stable Farabi-generated key, persisted before provider call | NULL; do not derive from execution ID unless explicitly approved as a migration strategy |
| outcome_certainty | One of NOT_SUBMITTED, UNKNOWN, CONFIRMED | NULL until a reconciliation/classification process can establish the meaning |
| cancel_requested_at | Set transactionally before provider cancellation dispatch | NULL |
| cancel_requested_by | Trusted authenticated actor or explicit system actor | NULL |
| last_reconciled_at | Set only after a provider observation or documented lookup attempt | NULL |
| reconciliation_attempt | Increment atomically for each durable attempt | 0 |

NOT_SUBMITTED means Farabi can prove no provider submission was attempted/accepted. UNKNOWN means the provider may have accepted work but its outcome is not established. CONFIRMED means Farabi has authoritative provider evidence for the recorded remote state. These values describe certainty, not lifecycle status; they must not replace the existing status column.

The nullable certainty field is deliberate for legacy rows. A backfill that labels every historical PENDING row UNKNOWN or every SUBMITTED row CONFIRMED would assert facts the existing data does not reliably prove.

## Persistence API changes required with the migration

The migration alone is insufficient. The JDBC adapter and engine port need coordinated, transactionally safe operations:

1. **Create with metadata:** insert the execution and all required new-write metadata in one statement/transaction. No provider call before commit.
2. **Find:** select/map the new columns while continuing to read legacy NULL values.
3. **Record provider acceptance:** atomically attach provider job ID and update status/certainty, guarded by expected status and current ownership/fence where a worker owns the transition.
4. **Record cancellation intent:** idempotently set actor/time before dispatch; do not transition to CANCELLED on acknowledgement.
5. **Record reconciliation observation:** update certainty, provider ID if discovered, attempt count, and observation timestamp under a current lease/fence.
6. **Claim due work:** reuse V3's lease fields and fencing semantics; do not add a second lease system. Ensure reconciliation candidates include ambiguous PENDING rows without provider IDs, while the existing poller continues to require known IDs.
7. **Workspace scoping:** every read and update remains constrained by both execution ID and workspace ID; actor authorization stays in the engine/service layer.

The current ExecutionRepository.transition API is not enough to safely update all metadata. Prefer explicit intent/observation operations or an immutable metadata aggregate with a compare-and-set version, rather than adding many loosely related nullable parameters to transition.

## Migration and rollout order

1. Approve ADR-009 policy defaults and this field vocabulary.
2. Add the nullable V7 columns and indexes; deploy without changing runtime writes.
3. Update JDBC row mapping to tolerate NULL legacy metadata; verify old and new rows can be read.
4. Add engine contracts and repository operations; write metadata for new executions while retaining the existing submit/status behavior.
5. Add migration integration tests against PostgreSQL-compatible SQL, including unique-key and partial-index behavior. H2 tests alone are not sufficient evidence for PostgreSQL-specific migration semantics.
6. Add provider idempotency/correlation support and reconciliation in separate, feature-flagged increments.
7. Only after historical-row handling and operational monitoring are proven, consider constraints that require metadata for all new rows. Do not make legacy columns globally NOT NULL without a verified backfill.

## Required tests before accepting V7

- V1–V7 migrate from an empty database in order.
- Existing V1–V6 rows remain readable with NULL metadata.
- New execution metadata round-trips exactly, including UTC instants.
- Workspace + idempotency key uniqueness is enforced; NULL legacy keys do not collide.
- Negative reconciliation attempts are rejected.
- Deadline and reconciliation indexes are exercised by representative query plans.
- A failed insert leaves no partial execution row and provider submission is never called.
- Cancellation intent is durable before provider dispatch and repeated requests are idempotent.
- Reconciliation updates fail for stale lease fences.
- Cross-workspace metadata updates affect zero rows.
- PostgreSQL migration behavior is tested in PostgreSQL CI; embedded H2 tests remain useful for repository-level behavior but are not a substitute.

## Open decisions

- Is outcome_certainty sufficient, or should provider observation details be a separate append-only event table?
- Should reconciliation attempts and diagnostics be columns on the execution row, or a separate history table to preserve every observation?
- Should idempotency keys be globally unique or workspace-scoped? The proposal follows V6's workspace-scoped convention; provider semantics may require a different scope.
- Should cancel_requested_by store a subject ID, service principal, or a structured actor reference?
- Which provider terminal states count as authoritative confirmation, and how are providers with eventually consistent status APIs represented?
- Does an ambiguous PENDING row need a separate claim queue/index to avoid competing with normal polling?

These decisions must be closed before finalizing the migration.
