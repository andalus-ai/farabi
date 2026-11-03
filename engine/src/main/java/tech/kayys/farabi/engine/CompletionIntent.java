package tech.kayys.farabi.engine;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import tech.kayys.farabi.engine.CompletionReconciliation.Attempt;
import tech.kayys.farabi.engine.QueryContracts.Status;
import tech.kayys.farabi.engine.WorkerLeases.Lease;

/**
 * Durable intent for one RUNNING-to-terminal completion transition.
 *
 * <p>The intent is opened before the terminal write. It is not evidence that
 * the terminal write was attempted or failed.</p>
 */
public record CompletionIntent(
        Attempt attempt,
        Instant openedAt) {
    public CompletionIntent {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(openedAt, "openedAt");
    }

    public boolean matches(Lease lease) {
        Objects.requireNonNull(lease, "lease");
        return attempt.executionId().equals(lease.executionId())
                && attempt.workspaceId().equals(lease.workspaceId())
                && attempt.workerId().equals(lease.workerId())
                && attempt.leaseFence() == lease.fence();
    }

    /** Intent identity includes the exact expected and intended transition. */
    public boolean sameTransition(CompletionIntent other) {
        Objects.requireNonNull(other, "other");
        return attempt.equals(other.attempt);
    }

    public Status expectedStatus() {
        return attempt.expectedStatus();
    }

    public Status intendedStatus() {
        return attempt.intendedStatus();
    }
}
