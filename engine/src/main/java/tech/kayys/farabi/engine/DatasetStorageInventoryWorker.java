package tech.kayys.farabi.engine;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import tech.kayys.farabi.engine.DatasetStorageInventoryJobPorts.Job;
import tech.kayys.farabi.engine.DatasetStorageInventoryJobPorts.Lease;
import tech.kayys.farabi.engine.DatasetStorageInventoryJobPorts.Status;
import tech.kayys.farabi.engine.DatasetStorageInventoryJobPorts.Store;
import tech.kayys.farabi.engine.DatasetStorageInventoryAuditPorts.AuditLog;
import tech.kayys.farabi.engine.DatasetStorageInventoryAuditPorts.Event;
import tech.kayys.farabi.engine.DatasetStorageInventoryAuditPorts.EventType;

/** Executes inventory inspections under fenced leases; inspections have no destructive side effects. */
public final class DatasetStorageInventoryWorker {
    private final String workerId;
    private final Store jobs;
    private final DatasetStorageInventoryService inventory;
    private final Clock clock;
    private final Duration leaseDuration;
    private final AuditLog audit;
    public DatasetStorageInventoryWorker(String workerId, Store jobs, DatasetStorageInventoryService inventory,
            Clock clock, Duration leaseDuration, AuditLog audit) {
        if (workerId == null || workerId.isBlank()) throw new IllegalArgumentException("workerId required");
        this.workerId = workerId; this.jobs = Objects.requireNonNull(jobs); this.inventory = Objects.requireNonNull(inventory);
        this.clock = Objects.requireNonNull(clock); this.leaseDuration = Objects.requireNonNull(leaseDuration); this.audit = Objects.requireNonNull(audit);
        if (leaseDuration.isNegative() || leaseDuration.isZero()) throw new IllegalArgumentException("lease duration must be positive");
    }
    public boolean runOne() {
        var now = clock.instant();
        // Reap separately from claim so terminal lease expiry is observable and auditable.
        for (Job expired : jobs.failExpiredLeases(now)) {
            audit.append(new Event(UUID.randomUUID(), expired.workspaceId(), expired.id(), workerId,
                    EventType.FAILED, expired.attempts(), null, "LEASE_EXHAUSTED", now));
        }
        Job job = jobs.claim(new Lease(workerId, now, now.plus(leaseDuration))).orElse(null);
        if (job == null) return false;
        try {
            var report = inventory.inspect(job.workspaceId());
            if (jobs.complete(job.id(), job.leaseToken(), report.id(), clock.instant())) {
                audit.append(new Event(UUID.randomUUID(), job.workspaceId(), job.id(), workerId,
                        EventType.SUCCEEDED, job.attempts(), report.id(), null, clock.instant()));
            }
        } catch (RuntimeException failure) {
            int exponent = Math.min(Math.max(job.attempts() - 1, 0), 10);
            Duration backoff = Duration.ofSeconds(Math.min(3600L, 5L << exponent));
            String message = failure.getMessage() == null ? "inventory inspection failed" : failure.getMessage();
            if (message.length() > 1000) message = message.substring(0, 1000);
            boolean updated = jobs.fail(job.id(), job.leaseToken(), failure.getClass().getSimpleName(), message,
                    clock.instant().plus(backoff), clock.instant());
            if (updated) {
                boolean terminal = job.attempts() >= job.maxAttempts();
                audit.append(new Event(UUID.randomUUID(), job.workspaceId(), job.id(), workerId,
                        terminal ? EventType.FAILED : EventType.RETRY_SCHEDULED, job.attempts(), null,
                        failure.getClass().getSimpleName(), clock.instant()));
            }
        }
        return true;
    }
}
