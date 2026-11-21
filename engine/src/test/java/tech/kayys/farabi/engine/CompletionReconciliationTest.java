package tech.kayys.farabi.engine;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.CompletionReconciliation.*;
import tech.kayys.farabi.engine.QueryContracts.Status;

class CompletionReconciliationTest {
    private static final UUID ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final Instant NOW = Instant.parse("2026-11-02T12:00:00Z");
    private static final Instant EXPIRY = NOW.plusSeconds(30);
    private static final Attempt ATTEMPT =
        new Attempt(ID, "workspace-a", "worker-a", 7, Status.RUNNING, Status.SUCCEEDED);

    @Test
    void intendedTerminalStateWithClearedLeaseIsApplied() {
        assertEquals(Outcome.APPLIED, classify(snapshot(Status.SUCCEEDED, null, 7, null)));
    }

    @Test
    void expectedStateWithSameUnexpiredLeaseIsNotApplied() {
        assertEquals(Outcome.NOT_APPLIED, classify(snapshot(Status.RUNNING, "worker-a", 7, EXPIRY)));
    }

    @Test
    void exactLeaseExpiryBoundaryIsNotAppliedAsRetryable() {
        assertEquals(Outcome.UNRESOLVED,
            CompletionReconciliation.classify(ATTEMPT,
                ReadResult.available(snapshot(Status.RUNNING, "worker-a", 7, NOW)), NOW));
    }

    @Test
    void unavailableAndMissingReadsRemainUnresolved() {
        assertEquals(Outcome.UNRESOLVED,
            CompletionReconciliation.classify(ATTEMPT, ReadResult.unavailable(), NOW));
        assertEquals(Outcome.UNRESOLVED,
            CompletionReconciliation.classify(ATTEMPT, ReadResult.missing(), NOW));
    }

    @Test
    void newerFenceOrDifferentOwnerCannotAuthorizeRetry() {
        assertEquals(Outcome.UNRESOLVED, classify(snapshot(Status.RUNNING, "worker-b", 8, EXPIRY)));
        assertEquals(Outcome.UNRESOLVED, classify(snapshot(Status.RUNNING, "worker-b", 7, EXPIRY)));
    }

    @Test
    void conflictingTerminalStateRemainsUnresolved() {
        assertEquals(Outcome.UNRESOLVED, classify(snapshot(Status.FAILED, null, 7, null)));
    }

    @Test
    void identityMismatchRemainsUnresolved() {
        Snapshot wrongWorkspace = new Snapshot(ID, "workspace-b", Status.SUCCEEDED, null, 7, null);
        Snapshot wrongExecution = new Snapshot(UUID.randomUUID(), "workspace-a", Status.SUCCEEDED, null, 7, null);
        assertEquals(Outcome.UNRESOLVED,
            CompletionReconciliation.classify(ATTEMPT, ReadResult.available(wrongWorkspace), NOW));
        assertEquals(Outcome.UNRESOLVED,
            CompletionReconciliation.classify(ATTEMPT, ReadResult.available(wrongExecution), NOW));
    }

    @Test
    void malformedLeaseMetadataAndInvalidAttemptAreRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new Snapshot(ID, "workspace-a", Status.RUNNING, "worker-a", 7, null));
        assertThrows(IllegalArgumentException.class,
            () -> new Attempt(ID, "workspace-a", "worker-a", 7, Status.SUBMITTED, Status.SUCCEEDED));
        assertThrows(IllegalArgumentException.class,
            () -> new Attempt(ID, "workspace-a", "worker-a", 0, Status.RUNNING, Status.SUCCEEDED));
    }

    private static Outcome classify(Snapshot snapshot) {
        return CompletionReconciliation.classify(ATTEMPT, ReadResult.available(snapshot), NOW);
    }

    private static Snapshot snapshot(Status status, String owner, long fence, Instant expiry) {
        return new Snapshot(ID, "workspace-a", status, owner, fence, expiry);
    }
}
