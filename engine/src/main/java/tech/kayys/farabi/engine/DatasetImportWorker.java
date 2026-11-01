package tech.kayys.farabi.engine;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import tech.kayys.farabi.engine.DatasetContracts.Profile;
import tech.kayys.farabi.engine.DatasetImportJobPorts.*;

/** Executes one durable profiling job. Multiple instances may safely compete for work. */
public final class DatasetImportWorker {
    private final String workerId;
    private final Store jobs;
    private final DatasetPorts.Catalog catalog;
    private final SourceResolver sources;
    private final DatasetImportPorts.Profiler profiler;
    private final Clock clock;
    private final Duration leaseDuration;

    public DatasetImportWorker(String workerId,Store jobs,DatasetPorts.Catalog catalog,
            SourceResolver sources,DatasetImportPorts.Profiler profiler,Clock clock,Duration leaseDuration) {
        QueryContracts.required(workerId,"workerId");
        this.workerId=workerId;this.jobs=Objects.requireNonNull(jobs);this.catalog=Objects.requireNonNull(catalog);
        this.sources=Objects.requireNonNull(sources);this.profiler=Objects.requireNonNull(profiler);
        this.clock=Objects.requireNonNull(clock);this.leaseDuration=Objects.requireNonNull(leaseDuration);
        if(leaseDuration.isZero()||leaseDuration.isNegative())throw new IllegalArgumentException("leaseDuration must be positive");
    }

    /** Returns the claimed job ID, or empty if the queue has no available work. */
    public Optional<java.util.UUID> runOne() {
        var now=clock.instant();
        Optional<Job> claimed=jobs.claim(new Lease(workerId,now,now.plus(leaseDuration)));
        if(claimed.isEmpty())return Optional.empty();
        Job job=claimed.get();
        try {
            var dataset=catalog.find(job.datasetId(),job.workspaceId())
                    .orElseThrow(()->new IllegalStateException("dataset not found in workspace"));
            var staged=sources.resolve(dataset);
            Profile profile=profiler.profile(dataset.id(),dataset.workspaceId(),staged);
            if(!dataset.id().equals(profile.datasetId())||!dataset.workspaceId().equals(profile.workspaceId()))
                throw new IllegalArgumentException("profiler returned profile for another dataset or workspace");
            catalog.saveProfile(profile);
            jobs.complete(job.id(),job.leaseToken(),clock.instant());
        } catch(Exception e) {
            jobs.fail(job.id(),job.leaseToken(),"PROFILE_FAILED",safeMessage(e),clock.instant());
        }
        return Optional.of(job.id());
    }

    private static String safeMessage(Exception e) {
        String message=e.getMessage();
        if(message==null||message.isBlank())return e.getClass().getSimpleName();
        return message.length()>2000?message.substring(0,2000):message;
    }
}
