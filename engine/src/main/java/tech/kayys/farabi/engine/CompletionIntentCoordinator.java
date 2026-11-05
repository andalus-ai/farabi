package tech.kayys.farabi.engine;

import java.time.Instant;
import java.util.Objects;
import tech.kayys.farabi.engine.CompletionReconciliation.Outcome;
import tech.kayys.farabi.engine.QueryPorts.CompletionIntentStore;
import tech.kayys.farabi.engine.QueryPorts.CompletionWriteResult;
import tech.kayys.farabi.engine.QueryPorts.IntentOpenResult;
import tech.kayys.farabi.engine.WorkerLeases.Lease;

/**
 * Enforces the durable-intent-before-terminal-write protocol.
 *
 * <p>This coordinator performs at most one completion write and, when its
 * result is uncertain or explicitly not applied, at most one read-only
 * reconciliation. It never retries, renews a lease, or schedules work.</p>
 */
public final class CompletionIntentCoordinator {
    private final CompletionIntentStore store;
    private final CompletionReconciler reconciler;

    public CompletionIntentCoordinator(
            CompletionIntentStore store,
            CompletionReconciler reconciler) {
        this.store = Objects.requireNonNull(store, "store");
        this.reconciler = Objects.requireNonNull(reconciler, "reconciler");
    }

    public Result complete(Lease lease, CompletionIntent intent, Instant now) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(now, "now");

        if (!intent.matches(lease)) {
            return new Result(Decision.LEASE_MISMATCH, null);
        }

        final IntentOpenResult opened;
        try {
            opened = Objects.requireNonNull(
                    store.open(lease, intent, now), "intent open result");
        } catch (RuntimeException exception) {
            // The intent may or may not have committed. Without an
            // authoritative intent read, proceeding would be unsafe.
            return new Result(Decision.OPEN_UNCERTAIN, null);
        }

        if (opened == IntentOpenResult.LEASE_LOST) {
            return new Result(Decision.LEASE_LOST, null);
        }
        if (opened == IntentOpenResult.CONFLICT) {
            return new Result(Decision.INTENT_CONFLICT, null);
        }

        final CompletionWriteResult written;
        try {
            written = Objects.requireNonNull(
                    store.completeAndResolve(lease, intent, now),
                    "completion write result");
        } catch (RuntimeException exception) {
            return reconcile(intent, now);
        }

        return switch (written) {
            case APPLIED -> new Result(Decision.COMPLETED, null);
            case LEASE_LOST -> new Result(Decision.LEASE_LOST, null);
            case INTENT_MISSING -> new Result(Decision.INTENT_MISSING, null);
            case NOT_APPLIED -> reconcile(intent, now);
        };
    }

    private Result reconcile(CompletionIntent intent, Instant now) {
        CompletionReconciler.Result observed = reconciler.reconcile(intent.attempt(), now);
        return switch (observed.outcome()) {
            case APPLIED -> new Result(Decision.COMPLETED, observed);
            case NOT_APPLIED -> new Result(Decision.RETRY_ELIGIBLE, observed);
            case UNRESOLVED -> new Result(Decision.UNRESOLVED, observed);
        };
    }

    public enum Decision {
        COMPLETED,
        RETRY_ELIGIBLE,
        UNRESOLVED,
        OPEN_UNCERTAIN,
        LEASE_MISMATCH,
        LEASE_LOST,
        INTENT_CONFLICT,
        INTENT_MISSING
    }

    public record Result(Decision decision, CompletionReconciler.Result reconciliation) {
        public Result {
            Objects.requireNonNull(decision, "decision");
            if ((decision == Decision.RETRY_ELIGIBLE || decision == Decision.UNRESOLVED)
                    != (reconciliation != null)) {
                throw new IllegalArgumentException(
                        "reconciliation evidence is required only for reconciled decisions");
            }
            if (reconciliation != null) {
                if (decision == Decision.RETRY_ELIGIBLE
                        && reconciliation.outcome() != Outcome.NOT_APPLIED) {
                    throw new IllegalArgumentException("retry eligibility requires NOT_APPLIED");
                }
                if (decision == Decision.UNRESOLVED
                        && reconciliation.outcome() != Outcome.UNRESOLVED) {
                    throw new IllegalArgumentException("unresolved decision requires UNRESOLVED");
                }
            }
        }

        /** Eligibility only; this result never performs a retry. */
        public boolean mayRetryCompletion() {
            return decision == Decision.RETRY_ELIGIBLE;
        }

        public boolean isComplete() {
            return decision == Decision.COMPLETED;
        }
    }
}
