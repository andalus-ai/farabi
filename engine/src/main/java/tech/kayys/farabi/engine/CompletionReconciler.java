package tech.kayys.farabi.engine;

import java.time.Instant;
import java.util.Objects;
import tech.kayys.farabi.engine.CompletionReconciliation.Attempt;
import tech.kayys.farabi.engine.CompletionReconciliation.Outcome;
import tech.kayys.farabi.engine.CompletionReconciliation.ReadResult;
import tech.kayys.farabi.engine.CompletionReconciliation.ReadState;
import tech.kayys.farabi.engine.QueryPorts.CompletionReconciliationReader;

/**
 * Performs one read-only reconciliation observation for an uncertain completion.
 *
 * <p>This coordinator never retries a transition, renews/releases a lease, or
 * schedules work. A caller must treat UNRESOLVED as a stop signal.</p>
 */
public final class CompletionReconciler {
    private final CompletionReconciliationReader reader;

    public CompletionReconciler(CompletionReconciliationReader reader) {
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    /**
     * Reads authoritative state once and classifies it at the supplied instant.
     * Reader failures are uncertainty, not evidence of a failed completion.
     */
    public Result reconcile(Attempt attempt, Instant observedAt) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(observedAt, "observedAt");

        final ReadResult read;
        try {
            read = Objects.requireNonNull(
                    reader.read(attempt.executionId(), attempt.workspaceId()),
                    "reconciliation reader result");
        } catch (RuntimeException exception) {
            return new Result(Outcome.UNRESOLVED, ReadState.UNAVAILABLE);
        }

        return new Result(
                CompletionReconciliation.classify(attempt, read, observedAt),
                read.state());
    }

    /** A decision plus the quality of the authoritative read that informed it. */
    public record Result(Outcome outcome, ReadState readState) {
        public Result {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(readState, "readState");
            if (readState != ReadState.AVAILABLE && outcome != Outcome.UNRESOLVED) {
                throw new IllegalArgumentException("non-available reads must remain unresolved");
            }
        }

        public boolean mayRetryCompletion() {
            return outcome == Outcome.NOT_APPLIED;
        }

        public boolean isComplete() {
            return outcome == Outcome.APPLIED;
        }
    }
}
