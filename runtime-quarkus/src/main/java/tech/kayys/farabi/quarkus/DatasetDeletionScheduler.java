package tech.kayys.farabi.quarkus;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.farabi.engine.DatasetDeletionWorker;

/** Drains durable object deletion jobs using database-backed leases. */
@ApplicationScoped
public class DatasetDeletionScheduler {
    @Inject DatasetDeletionWorker worker;

    @Scheduled(every="{farabi.import.poll-interval}", concurrentExecution=Scheduled.ConcurrentExecution.SKIP,
            identity="farabi-dataset-deletion-worker")
    void poll() {
        for (int i=0; i<10; i++) if (!worker.runOne()) return;
    }
}
