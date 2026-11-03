package tech.kayys.farabi.engine;

import java.util.Optional;
import java.util.UUID;
import tech.kayys.farabi.engine.QueryContracts.*;

public final class QueryPorts {
    private QueryPorts() {}
    @FunctionalInterface public interface Authorizer { void authorize(Request request); }
    @FunctionalInterface public interface Compiler { Plan compile(Request request); }
    @FunctionalInterface public interface Executor { Submission submit(Plan plan); }
    public interface ExecutionRepository {
        void create(Execution execution);
        Optional<Execution> find(UUID id, String workspaceId);
        boolean transition(UUID id, String workspaceId, Status expected, Status next, String providerJobId);
    }

    /**
     * Reads lifecycle and lease ownership/fencing metadata as one authoritative
     * workspace-scoped observation after an uncertain completion write.
     */
    @FunctionalInterface
    public interface CompletionReconciliationReader {
        CompletionReconciliation.ReadResult read(UUID executionId, String workspaceId);
    }

    /**
     * Persistence boundary for durable completion intent.
     *
     * <p>Implementations must scope every operation by execution and workspace,
     * and enforce the supplied V3 lease owner/fence with an exclusive expiry
     * predicate. An exception means the caller cannot infer whether a write
     * committed; it must reconcile through a fresh authoritative read.</p>
     */
    public interface CompletionIntentStore {
        /**
         * Durably opens the exact completion intent before any terminal write.
         * Repeating the same intent is idempotent; a different intent for an
         * already-open episode must be reported as CONFLICT.
         */
        IntentOpenResult open(WorkerLeases.Lease lease,
                              CompletionIntent intent,
                              java.time.Instant now);

        /**
         * Atomically applies the expected-to-terminal lifecycle transition and
         * resolves its intent in one database transaction.
         */
        CompletionWriteResult completeAndResolve(WorkerLeases.Lease lease,
                                                  CompletionIntent intent,
                                                  java.time.Instant now);
    }

    public enum IntentOpenResult {
        OPENED,
        ALREADY_OPEN,
        LEASE_LOST,
        CONFLICT
    }

    public enum CompletionWriteResult {
        APPLIED,
        NOT_APPLIED,
        INTENT_MISSING,
        LEASE_LOST
    }
}
