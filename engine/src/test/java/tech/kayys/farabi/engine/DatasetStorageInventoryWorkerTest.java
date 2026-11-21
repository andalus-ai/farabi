package tech.kayys.farabi.engine;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.DatasetStorageInventoryJobPorts.Job;
import tech.kayys.farabi.engine.DatasetStorageInventoryJobPorts.Lease;
import tech.kayys.farabi.engine.DatasetStorageInventoryJobPorts.Status;
import tech.kayys.farabi.engine.DatasetStorageInventoryService.Report;

class DatasetStorageInventoryWorkerTest {
    @Test void persistsReportAndCompletesUsingClaimedLeaseToken() {
        Instant now = Instant.parse("2026-11-01T00:00:00Z");
        UUID id = UUID.randomUUID(), token = UUID.randomUUID();
        Job job = new Job(id, "workspace-a", "request-1", Status.RUNNING, 1, 3,
                "worker-a", token, now.plusSeconds(120), now, null, null, null, now, now);
        final UUID[] completedReport = new UUID[1];
        final UUID[] completedToken = new UUID[1];
        final boolean[] claimed = new boolean[1];
        DatasetStorageInventoryJobPorts.Store store = new DatasetStorageInventoryJobPorts.Store() {
            public Job enqueue(String w, String k, int m, Instant n) { throw new UnsupportedOperationException(); }
            public Optional<Job> find(UUID i, String w) { return Optional.empty(); }
            public List<Job> failExpiredLeases(Instant at) { return List.of(); }
            public Optional<Job> claim(Lease lease) { assertEquals("worker-a", lease.workerId()); if (claimed[0]) return Optional.empty(); claimed[0] = true; return Optional.of(job); }
            public boolean complete(UUID i, UUID t, UUID report, Instant n) {
                completedToken[0] = t; completedReport[0] = report; return true;
            }
            public boolean fail(UUID i, UUID t, String c, String m, Instant r, Instant n) { org.junit.jupiter.api.Assertions.fail("unexpected failure"); return false; }
        };
        List<Report> saved = new java.util.ArrayList<>();
        DatasetStorageInventoryService inventory = new DatasetStorageInventoryService(
                workspace -> List.of(), new DatasetStorageInventoryPorts.Inventory() {
                    public Optional<DatasetStorageInventoryPorts.ObjectEntry> inspect(String ref, String workspace) { return Optional.empty(); }
                    public List<DatasetStorageInventoryPorts.ObjectEntry> list(String workspace) { return List.of(); }
                }, Clock.fixed(now, ZoneOffset.UTC), Duration.ofHours(24), new DatasetStorageInventoryPorts.Reports() {
                    public void save(Report report) { saved.add(report); }
                    public List<Report> recent(String workspace, int limit) { return List.copyOf(saved); }
                });
        DatasetStorageInventoryWorker worker = new DatasetStorageInventoryWorker(
                "worker-a", store, inventory, Clock.fixed(now, ZoneOffset.UTC), Duration.ofMinutes(2),
                new DatasetStorageInventoryAuditPorts.AuditLog() {
                    public void append(DatasetStorageInventoryAuditPorts.Event event) { }
                    public List<DatasetStorageInventoryAuditPorts.Event> recent(String workspace, UUID jobId, int limit) { return List.of(); }
                });

        assertTrue(worker.runOne());
        assertEquals(token, completedToken[0]);
        assertNotNull(completedReport[0]);
        assertEquals(completedReport[0], saved.get(0).id());
        assertFalse(worker.runOne());
    }
    @Test void auditsExpiredFinalAttemptLeasesBeforeClaimingNewWork() {
        Instant now = Instant.parse("2026-11-01T00:00:00Z");
        Job expired = new Job(UUID.randomUUID(), "workspace-a", "request-expired", Status.FAILED,
                3, 3, null, null, null, now, null, "LEASE_EXHAUSTED",
                "worker lease expired after final attempt", now, now);
        final List<DatasetStorageInventoryAuditPorts.Event> events = new java.util.ArrayList<>();
        DatasetStorageInventoryJobPorts.Store store = new DatasetStorageInventoryJobPorts.Store() {
            public Job enqueue(String w, String k, int m, Instant n) { throw new UnsupportedOperationException(); }
            public Optional<Job> find(UUID id, String w) { return Optional.empty(); }
            public List<Job> failExpiredLeases(Instant at) { assertEquals(now, at); return List.of(expired); }
            public Optional<Job> claim(Lease lease) { return Optional.empty(); }
            public boolean complete(UUID id, UUID token, UUID report, Instant at) { return false; }
            public boolean fail(UUID id, UUID token, String code, String message, Instant retry, Instant at) { return false; }
        };
        DatasetStorageInventoryService inventory = new DatasetStorageInventoryService(
                workspace -> List.of(), new DatasetStorageInventoryPorts.Inventory() {
                    public Optional<DatasetStorageInventoryPorts.ObjectEntry> inspect(String ref, String workspace) { return Optional.empty(); }
                    public List<DatasetStorageInventoryPorts.ObjectEntry> list(String workspace) { return List.of(); }
                }, Clock.fixed(now, ZoneOffset.UTC), Duration.ofHours(24));
        DatasetStorageInventoryWorker worker = new DatasetStorageInventoryWorker("worker-a", store, inventory,
                Clock.fixed(now, ZoneOffset.UTC), Duration.ofMinutes(2),
                new DatasetStorageInventoryAuditPorts.AuditLog() {
                    public void append(DatasetStorageInventoryAuditPorts.Event event) { events.add(event); }
                    public List<DatasetStorageInventoryAuditPorts.Event> recent(String workspace, UUID jobId, int limit) { return List.of(); }
                });

        assertFalse(worker.runOne());
        assertEquals(1, events.size());
        assertEquals(DatasetStorageInventoryAuditPorts.EventType.FAILED, events.get(0).type());
        assertEquals("LEASE_EXHAUSTED", events.get(0).errorCode());
        assertEquals(3, events.get(0).attempt());
        assertEquals(expired.id(), events.get(0).jobId());
    }

}
