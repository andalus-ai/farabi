package tech.kayys.farabi.engine;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.CompletionReconciliation.*;
import tech.kayys.farabi.engine.QueryContracts.Status;

class CompletionReconcilerTest {
    private static final UUID ID =
            UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final Instant NOW = Instant.parse("2026-11-03T00:00:00Z");
    private static final Attempt ATTEMPT =
            new Attempt(ID, "workspace-a", "worker-a", 3, Status.RUNNING, Status.SUCCEEDED);

    @Test
    void confirmsAppliedWithoutAuthorizingAnotherCompletion() {
        AtomicInteger reads = new AtomicInteger();
        CompletionReconciler reconciler = new CompletionReconciler((id, workspace) -> {
            reads.incrementAndGet();
            assertEquals(ID, id);
            assertEquals("workspace-a", workspace);
            return ReadResult.available(snapshot(Status.SUCCEEDED, null, 3, null));
        });

        CompletionReconciler.Result result = reconciler.reconcile(ATTEMPT, NOW);

        assertEquals(Outcome.APPLIED, result.outcome());
        assertEquals(ReadState.AVAILABLE, result.readState());
        assertTrue(result.isComplete());
        assertFalse(result.mayRetryCompletion());
        assertEquals(1, reads.get(), "one reconciliation call performs exactly one read");
    }

    @Test
    void reportsNotAppliedOnlyWhenOriginalLeaseRemainsValid() {
        CompletionReconciler reconciler = new CompletionReconciler(
                (id, workspace) -> ReadResult.available(
                        snapshot(Status.RUNNING, "worker-a", 3, NOW.plusSeconds(10))));

        CompletionReconciler.Result result = reconciler.reconcile(ATTEMPT, NOW);

        assertEquals(Outcome.NOT_APPLIED, result.outcome());
        assertTrue(result.mayRetryCompletion(),
                "the result only identifies eligibility; it does not execute a retry");
        assertFalse(result.isComplete());
    }

    @Test
    void missingAndUnavailableReadsNeverAuthorizeRetry() {
        for (ReadResult read : new ReadResult[] {
                ReadResult.missing(), ReadResult.unavailable() }) {
            CompletionReconciler reconciler = new CompletionReconciler((id, workspace) -> read);
            CompletionReconciler.Result result = reconciler.reconcile(ATTEMPT, NOW);
            assertEquals(Outcome.UNRESOLVED, result.outcome());
            assertFalse(result.mayRetryCompletion());
            assertFalse(result.isComplete());
        }
    }

    @Test
    void readerExceptionAndNullResultBecomeUnresolved() {
        CompletionReconciler throwing = new CompletionReconciler((id, workspace) -> {
            throw new IllegalStateException("database unavailable");
        });
        CompletionReconciler.Result failed = throwing.reconcile(ATTEMPT, NOW);
        assertEquals(Outcome.UNRESOLVED, failed.outcome());
        assertEquals(ReadState.UNAVAILABLE, failed.readState());
        assertFalse(failed.mayRetryCompletion());

        CompletionReconciler nullReturning = new CompletionReconciler((id, workspace) -> null);
        assertEquals(Outcome.UNRESOLVED,
                nullReturning.reconcile(ATTEMPT, NOW).outcome());
    }

    @Test
    void conflictingStateAndExpiredLeaseRemainUnresolved() {
        CompletionReconciler conflict = new CompletionReconciler(
                (id, workspace) -> ReadResult.available(
                        snapshot(Status.FAILED, null, 3, null)));
        CompletionReconciler expired = new CompletionReconciler(
                (id, workspace) -> ReadResult.available(
                        snapshot(Status.RUNNING, "worker-a", 3, NOW)));

        assertEquals(Outcome.UNRESOLVED, conflict.reconcile(ATTEMPT, NOW).outcome());
        assertEquals(Outcome.UNRESOLVED, expired.reconcile(ATTEMPT, NOW).outcome());
    }

    @Test
    void rejectsNullDependenciesAndInputs() {
        assertThrows(NullPointerException.class, () -> new CompletionReconciler(null));
        CompletionReconciler reconciler = new CompletionReconciler(
                (id, workspace) -> ReadResult.unavailable());
        assertThrows(NullPointerException.class, () -> reconciler.reconcile(null, NOW));
        assertThrows(NullPointerException.class, () -> reconciler.reconcile(ATTEMPT, null));
    }

    private static Snapshot snapshot(Status status, String owner, long fence, Instant expiry) {
        return new Snapshot(ID, "workspace-a", status, owner, fence, expiry);
    }
}
