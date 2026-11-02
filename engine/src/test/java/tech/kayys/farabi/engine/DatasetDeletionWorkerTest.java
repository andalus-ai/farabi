package tech.kayys.farabi.engine;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.DatasetStorageLifecyclePorts.*;

class DatasetDeletionWorkerTest {
    private final Instant now=Instant.parse("2026-11-02T00:00:00Z");

    @Test void successfulObjectDeletionCompletesWithTheClaimedFence() {
        UUID token=UUID.randomUUID();
        RecordingStore store=new RecordingStore(deletion(token,1));
        DatasetDeletionWorker worker=new DatasetDeletionWorker(store,(ref,ws)->{},Clock.fixed(now,ZoneOffset.UTC),"worker-a",Duration.ofMinutes(2));
        assertTrue(worker.runOne());
        assertEquals(token,store.completedToken);
        assertEquals(1,store.completeCalls);
        assertEquals(0,store.failCalls);
    }

    @Test void objectStoreFailureSchedulesBoundedRetryUsingClaimedFence() {
        UUID token=UUID.randomUUID();
        RecordingStore store=new RecordingStore(deletion(token,2));
        DatasetDeletionWorker worker=new DatasetDeletionWorker(store,(ref,ws)->{throw new IllegalStateException("provider detail must not escape");},
                Clock.fixed(now,ZoneOffset.UTC),"worker-b",Duration.ofMinutes(2));
        assertTrue(worker.runOne());
        assertEquals(token,store.failedToken);
        assertEquals(now.plusSeconds(10),store.retryAt);
        assertEquals(1,store.failCalls);
        assertEquals(0,store.completeCalls);
    }

    @Test void noClaimedJobDoesNoObjectStoreWork() {
        RecordingStore store=new RecordingStore(null);
        DatasetDeletionWorker worker=new DatasetDeletionWorker(store,(ref,ws)->fail("must not delete"),
                Clock.fixed(now,ZoneOffset.UTC),"worker-c",Duration.ofMinutes(2));
        assertFalse(worker.runOne());
        assertEquals(0,store.completeCalls);
        assertEquals(0,store.failCalls);
    }

    private Deletion deletion(UUID token,int attempts) {
        return new Deletion(UUID.randomUUID(),UUID.randomUUID(),"workspace-a","local-file:workspace-a:object",
                "RUNNING",attempts,5,token,now.plusSeconds(120),now);
    }

    private static final class RecordingStore implements Store {
        private final Deletion claimed;
        private UUID completedToken,failedToken;
        private Instant retryAt;
        private int completeCalls,failCalls;
        RecordingStore(Deletion claimed){this.claimed=claimed;}
        public Optional<Deletion> request(UUID id,String ws,Instant now,int max){throw new UnsupportedOperationException();}
        public Optional<Deletion> claim(Lease lease){return Optional.ofNullable(claimed);}
        public boolean complete(UUID id,UUID token,Instant now){completeCalls++;completedToken=token;return true;}
        public boolean fail(UUID id,UUID token,Instant retry,Instant now){failCalls++;failedToken=token;retryAt=retry;return true;}
    }
}
