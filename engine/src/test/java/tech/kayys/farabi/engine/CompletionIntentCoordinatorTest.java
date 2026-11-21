package tech.kayys.farabi.engine;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.CompletionIntentCoordinator.Decision;
import tech.kayys.farabi.engine.CompletionReconciliation.*;
import tech.kayys.farabi.engine.QueryContracts.Status;
import tech.kayys.farabi.engine.QueryPorts.*;
import tech.kayys.farabi.engine.WorkerLeases.Lease;

class CompletionIntentCoordinatorTest {
    private static final UUID ID =
            UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
    private static final Instant NOW = Instant.parse("2026-11-05T08:00:00Z");
    private static final Lease LEASE =
            new Lease(ID, "workspace-a", "worker-a", 9, NOW.plusSeconds(30));
    private static final CompletionIntent INTENT = new CompletionIntent(
            new Attempt(ID, "workspace-a", "worker-a", 9, Status.RUNNING, Status.SUCCEEDED), NOW);

    @Test
    void opensIntentBeforeApplyingCompletion() {
        List<String> calls = new ArrayList<>();
        CompletionIntentStore store = new Store(calls) {
            @Override public CompletionWriteResult completeAndResolve(
                    Lease lease, CompletionIntent intent, Instant now) {
                calls.add("complete");
                return CompletionWriteResult.APPLIED;
            }
        };

        CompletionIntentCoordinator.Result result = coordinator(store, available(
                snapshot(Status.RUNNING, "worker-a", 9, NOW.plusSeconds(30))))
                .complete(LEASE, INTENT, NOW);

        assertEquals(Decision.COMPLETED, result.decision());
        assertEquals(List.of("open", "complete"), calls);
    }

    @Test
    void identicalAlreadyOpenIntentMayProceedIdempotently() {
        List<String> calls = new ArrayList<>();
        CompletionIntentStore store = new Store(calls) {
            @Override public IntentOpenResult open(Lease lease, CompletionIntent intent, Instant now) {
                calls.add("open");
                return IntentOpenResult.ALREADY_OPEN;
            }
            @Override public CompletionWriteResult completeAndResolve(
                    Lease lease, CompletionIntent intent, Instant now) {
                calls.add("complete");
                return CompletionWriteResult.APPLIED;
            }
        };

        assertTrue(coordinator(store, available(
                snapshot(Status.RUNNING, "worker-a", 9, NOW.plusSeconds(30))))
                .complete(LEASE, INTENT, NOW).isComplete());
        assertEquals(List.of("open", "complete"), calls);
    }

    @Test
    void openExceptionStopsBeforeCompletionBecauseIntentCommitIsUnknown() {
        List<String> calls = new ArrayList<>();
        CompletionIntentStore store = new Store(calls) {
            @Override public IntentOpenResult open(Lease lease, CompletionIntent intent, Instant now) {
                calls.add("open");
                throw new IllegalStateException("lost acknowledgement");
            }
        };

        CompletionIntentCoordinator.Result result = coordinator(store, unavailable())
                .complete(LEASE, INTENT, NOW);

        assertEquals(Decision.OPEN_UNCERTAIN, result.decision());
        assertEquals(List.of("open"), calls);
    }

    @Test
    void leaseLossAndIntentConflictPreventTerminalWrite() {
        for (IntentOpenResult openResult : List.of(
                IntentOpenResult.LEASE_LOST, IntentOpenResult.CONFLICT)) {
            List<String> calls = new ArrayList<>();
            CompletionIntentStore store = new Store(calls) {
                @Override public IntentOpenResult open(Lease lease, CompletionIntent intent, Instant now) {
                    calls.add("open");
                    return openResult;
                }
            };

            Decision expected = openResult == IntentOpenResult.LEASE_LOST
                    ? Decision.LEASE_LOST : Decision.INTENT_CONFLICT;
            assertEquals(expected, coordinator(store, unavailable())
                    .complete(LEASE, INTENT, NOW).decision());
            assertEquals(List.of("open"), calls);
        }
    }

    @Test
    void mismatchedLeaseNeverTouchesPersistence() {
        List<String> calls = new ArrayList<>();
        CompletionIntentStore store = new Store(calls) {};
        Lease wrongFence = new Lease(ID, "workspace-a", "worker-a", 10, NOW.plusSeconds(30));

        assertEquals(Decision.LEASE_MISMATCH,
                coordinator(store, unavailable()).complete(wrongFence, INTENT, NOW).decision());
        assertTrue(calls.isEmpty());
    }

    @Test
    void explicitNotAppliedIsReconciledAndOnlyReportedAsEligibility() {
        List<String> calls = new ArrayList<>();
        CompletionIntentStore store = new Store(calls) {
            @Override public CompletionWriteResult completeAndResolve(
                    Lease lease, CompletionIntent intent, Instant now) {
                calls.add("complete");
                return CompletionWriteResult.NOT_APPLIED;
            }
        };

        CompletionIntentCoordinator.Result result = coordinator(store, available(
                snapshot(Status.RUNNING, "worker-a", 9, NOW.plusSeconds(10))))
                .complete(LEASE, INTENT, NOW);

        assertEquals(Decision.RETRY_ELIGIBLE, result.decision());
        assertTrue(result.mayRetryCompletion());
        assertFalse(result.isComplete());
        assertEquals(List.of("open", "complete"), calls);
    }

    @Test
    void lostCompletionAcknowledgementCanBeConfirmedByAuthoritativeRead() {
        List<String> calls = new ArrayList<>();
        CompletionIntentStore store = new Store(calls) {
            @Override public CompletionWriteResult completeAndResolve(
                    Lease lease, CompletionIntent intent, Instant now) {
                calls.add("complete");
                throw new IllegalStateException("commit acknowledgement lost");
            }
        };

        CompletionIntentCoordinator.Result result = coordinator(store, available(
                snapshot(Status.SUCCEEDED, null, 9, null)))
                .complete(LEASE, INTENT, NOW);

        assertEquals(Decision.COMPLETED, result.decision());
        assertTrue(result.isComplete());
        assertEquals(List.of("open", "complete"), calls);
    }

    @Test
    void failedReconciliationRemainsUnresolvedAndNeverAuthorizesRetry() {
        List<String> calls = new ArrayList<>();
        CompletionIntentStore store = new Store(calls) {
            @Override public CompletionWriteResult completeAndResolve(
                    Lease lease, CompletionIntent intent, Instant now) {
                calls.add("complete");
                throw new IllegalStateException("unknown commit result");
            }
        };

        CompletionIntentCoordinator.Result result = coordinator(store, unavailable())
                .complete(LEASE, INTENT, NOW);

        assertEquals(Decision.UNRESOLVED, result.decision());
        assertFalse(result.mayRetryCompletion());
    }

    @Test
    void explicitStoreOutcomesAreNotReinterpretedAsSuccess() {
        for (CompletionWriteResult writeResult : List.of(
                CompletionWriteResult.LEASE_LOST, CompletionWriteResult.INTENT_MISSING)) {
            List<String> calls = new ArrayList<>();
            CompletionIntentStore store = new Store(calls) {
                @Override public CompletionWriteResult completeAndResolve(
                        Lease lease, CompletionIntent intent, Instant now) {
                    calls.add("complete");
                    return writeResult;
                }
            };

            Decision expected = writeResult == CompletionWriteResult.LEASE_LOST
                    ? Decision.LEASE_LOST : Decision.INTENT_MISSING;
            assertEquals(expected, coordinator(store, unavailable())
                    .complete(LEASE, INTENT, NOW).decision());
            assertEquals(List.of("open", "complete"), calls);
        }
    }

    private static CompletionIntentCoordinator coordinator(
            CompletionIntentStore store, ReadResult read) {
        return new CompletionIntentCoordinator(store,
                new CompletionReconciler((id, workspace) -> read));
    }

    private static ReadResult available(Snapshot snapshot) {
        return ReadResult.available(snapshot);
    }

    private static CompletionReconciler coordinatorReader(ReadResult read) {
        return new CompletionReconciler((id, workspace) -> read);
    }

    private static ReadResult unavailable() {
        return ReadResult.unavailable();
    }

    private static Snapshot snapshot(Status status, String owner, long fence, Instant expiry) {
        return new Snapshot(ID, "workspace-a", status, owner, fence, expiry);
    }

    private abstract static class Store implements CompletionIntentStore {
        private final List<String> calls;
        Store(List<String> calls) { this.calls = calls; }

        @Override public IntentOpenResult open(Lease lease, CompletionIntent intent, Instant now) {
            calls.add("open");
            return IntentOpenResult.OPENED;
        }

        @Override public CompletionWriteResult completeAndResolve(
                Lease lease, CompletionIntent intent, Instant now) {
            calls.add("complete");
            return CompletionWriteResult.APPLIED;
        }
    }
}
