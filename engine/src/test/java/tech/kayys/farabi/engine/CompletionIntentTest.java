package tech.kayys.farabi.engine;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.CompletionReconciliation.Attempt;
import tech.kayys.farabi.engine.QueryContracts.Status;
import tech.kayys.farabi.engine.WorkerLeases.Lease;

class CompletionIntentTest {
    private static final UUID ID =
            UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final Instant NOW = Instant.parse("2026-11-03T08:00:00Z");

    private static Attempt attempt(long fence, Status target) {
        return new Attempt(ID, "workspace-a", "worker-a", fence, Status.RUNNING, target);
    }

    @Test
    void intentMatchesOnlyItsExecutionWorkspaceWorkerAndFence() {
        CompletionIntent intent = new CompletionIntent(attempt(7, Status.SUCCEEDED), NOW);

        assertTrue(intent.matches(new Lease(ID, "workspace-a", "worker-a", 7, NOW.plusSeconds(30))));
        assertFalse(intent.matches(new Lease(ID, "workspace-a", "worker-b", 7, NOW.plusSeconds(30))));
        assertFalse(intent.matches(new Lease(ID, "workspace-b", "worker-a", 7, NOW.plusSeconds(30))));
        assertFalse(intent.matches(new Lease(ID, "workspace-a", "worker-a", 8, NOW.plusSeconds(30))));
        assertFalse(intent.matches(new Lease(UUID.randomUUID(), "workspace-a", "worker-a", 7, NOW.plusSeconds(30))));
    }

    @Test
    void sameEpisodeRequiresTheExactExpectedAndIntendedTransition() {
        CompletionIntent original = new CompletionIntent(attempt(7, Status.SUCCEEDED), NOW);
        CompletionIntent same = new CompletionIntent(attempt(7, Status.SUCCEEDED), NOW.plusSeconds(5));
        CompletionIntent differentTarget = new CompletionIntent(attempt(7, Status.FAILED), NOW);

        assertTrue(original.sameTransition(same));
        assertFalse(original.sameTransition(differentTarget));
    }

    @Test
    void exposesExpectedAndIntendedLifecycleStatesWithoutChangingThem() {
        CompletionIntent intent = new CompletionIntent(attempt(7, Status.CANCELLED), NOW);

        assertEquals(Status.RUNNING, intent.expectedStatus());
        assertEquals(Status.CANCELLED, intent.intendedStatus());
    }

    @Test
    void rejectsMissingIntentData() {
        assertThrows(NullPointerException.class, () -> new CompletionIntent(null, NOW));
        assertThrows(NullPointerException.class, () -> new CompletionIntent(attempt(7, Status.SUCCEEDED), null));
    }
}
