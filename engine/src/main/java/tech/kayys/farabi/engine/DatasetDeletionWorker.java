package tech.kayys.farabi.engine;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import tech.kayys.farabi.engine.DatasetStorageLifecyclePorts.*;

/** Lease-fenced worker. Object deletion is idempotent; failed jobs use bounded exponential retry. */
public final class DatasetDeletionWorker {
    private final Store store;
    private final ObjectStore objects;
    private final Clock clock;
    private final String workerId;
    private final Duration leaseDuration;

    public DatasetDeletionWorker(Store store, ObjectStore objects, Clock clock, String workerId, Duration leaseDuration) {
        this.store=Objects.requireNonNull(store); this.objects=Objects.requireNonNull(objects);
        this.clock=Objects.requireNonNull(clock);
        if(workerId==null||workerId.isBlank()||leaseDuration==null||leaseDuration.isNegative()||leaseDuration.isZero())
            throw new IllegalArgumentException("invalid worker configuration");
        this.workerId=workerId; this.leaseDuration=leaseDuration;
    }

    public boolean runOne() {
        var now=clock.instant();
        Optional<Deletion> claimed=store.claim(new Lease(workerId,now,now.plus(leaseDuration)));
        if(claimed.isEmpty()) return false;
        Deletion job=claimed.get();
        try {
            objects.delete(job.locationRef(),job.workspaceId());
            if(!store.complete(job.id(),job.leaseToken(),clock.instant()))
                throw new IllegalStateException("deletion lease lost before completion");
        } catch(RuntimeException failure) {
            long seconds=Math.min(900L,5L << Math.min(8,Math.max(0,job.attempts()-1)));
            store.fail(job.id(),job.leaseToken(),clock.instant().plusSeconds(seconds),clock.instant());
        }
        return true;
    }
}
