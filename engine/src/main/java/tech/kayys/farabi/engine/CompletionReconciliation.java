package tech.kayys.farabi.engine;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import tech.kayys.farabi.engine.QueryContracts.Status;

/**
 * Engine-level classification of an uncertain query completion write.
 *
 * <p>This model describes persistence certainty, not query lifecycle state. It
 * deliberately does not add a value to {@link Status}.</p>
 */
public final class CompletionReconciliation {
    private CompletionReconciliation() {}

    public enum Outcome {
        APPLIED,
        NOT_APPLIED,
        UNRESOLVED
    }

    public enum ReadState {
        AVAILABLE,
        MISSING,
        UNAVAILABLE
    }

    /** Identity and lease data read together from the authoritative store. */
    public record Snapshot(
            UUID executionId,
            String workspaceId,
            Status status,
            String leaseOwner,
            long leaseFence,
            Instant leaseExpiresAt) {
        public Snapshot {
            Objects.requireNonNull(executionId, "executionId");
            QueryContracts.required(workspaceId, "workspaceId");
            Objects.requireNonNull(status, "status");
            if (leaseFence < 0) {
                throw new IllegalArgumentException("leaseFence must not be negative");
            }
            if ((leaseOwner == null) != (leaseExpiresAt == null)) {
                throw new IllegalArgumentException("lease owner and expiry must both be present or absent");
            }
            if (leaseOwner != null) {
                QueryContracts.required(leaseOwner, "leaseOwner");
            }
        }
    }

    /** A read result distinguishes absence and read failure from an available row. */
    public record ReadResult(ReadState state, Snapshot snapshot) {
        public ReadResult {
            Objects.requireNonNull(state, "state");
            if ((state == ReadState.AVAILABLE) != (snapshot != null)) {
                throw new IllegalArgumentException("available reads require exactly one snapshot");
            }
        }

        public static ReadResult available(Snapshot snapshot) {
            return new ReadResult(ReadState.AVAILABLE, Objects.requireNonNull(snapshot, "snapshot"));
        }

        public static ReadResult missing() {
            return new ReadResult(ReadState.MISSING, null);
        }

        public static ReadResult unavailable() {
            return new ReadResult(ReadState.UNAVAILABLE, null);
        }
    }

    /** The exact conditional transition whose acknowledgement became uncertain. */
    public record Attempt(
            UUID executionId,
            String workspaceId,
            String workerId,
            long leaseFence,
            Status expectedStatus,
            Status intendedStatus) {
        public Attempt {
            Objects.requireNonNull(executionId, "executionId");
            QueryContracts.required(workspaceId, "workspaceId");
            QueryContracts.required(workerId, "workerId");
            if (leaseFence < 1) {
                throw new IllegalArgumentException("leaseFence must be positive");
            }
            Objects.requireNonNull(expectedStatus, "expectedStatus");
            Objects.requireNonNull(intendedStatus, "intendedStatus");
            if (expectedStatus != Status.RUNNING
                    || (intendedStatus != Status.SUCCEEDED
                    && intendedStatus != Status.FAILED
                    && intendedStatus != Status.CANCELLED)) {
                throw new IllegalArgumentException("completion reconciliation requires RUNNING to a terminal state");
            }
        }
    }

    /**
     * Classifies one authoritative observation. NOT_APPLIED is returned only
     * when the original worker still owns the same unexpired fence.
     */
    public static Outcome classify(Attempt attempt, ReadResult read, Instant observedAt) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(read, "read");
        Objects.requireNonNull(observedAt, "observedAt");

        if (read.state() != ReadState.AVAILABLE) {
            return Outcome.UNRESOLVED;
        }

        Snapshot snapshot = read.snapshot();
        if (!attempt.executionId().equals(snapshot.executionId())
                || !attempt.workspaceId().equals(snapshot.workspaceId())
                || snapshot.leaseFence() != attempt.leaseFence()) {
            return Outcome.UNRESOLVED;
        }

        if (snapshot.status() == attempt.intendedStatus()
                && snapshot.leaseOwner() == null
                && snapshot.leaseExpiresAt() == null) {
            return Outcome.APPLIED;
        }

        if (snapshot.status() == attempt.expectedStatus()
                && attempt.workerId().equals(snapshot.leaseOwner())
                && snapshot.leaseExpiresAt() != null
                && snapshot.leaseExpiresAt().isAfter(observedAt)) {
            return Outcome.NOT_APPLIED;
        }

        return Outcome.UNRESOLVED;
    }
}
