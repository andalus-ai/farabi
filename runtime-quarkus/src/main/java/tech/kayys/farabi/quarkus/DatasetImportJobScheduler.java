package tech.kayys.farabi.quarkus;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.farabi.engine.DatasetImportWorker;

/** Polls the durable queue; database leases coordinate multiple application replicas. */
@ApplicationScoped
public class DatasetImportJobScheduler {
    @Inject DatasetImportWorker worker;

    @Scheduled(every="{farabi.import.poll-interval}", concurrentExecution=Scheduled.ConcurrentExecution.SKIP,
            identity="farabi-dataset-import-worker")
    void poll() {
        for (int i=0; i<10; i++) {
            if (worker.runOne().isEmpty()) return;
        }
    }
}
