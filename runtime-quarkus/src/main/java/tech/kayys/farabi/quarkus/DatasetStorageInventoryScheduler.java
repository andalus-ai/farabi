package tech.kayys.farabi.quarkus;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import tech.kayys.farabi.engine.DatasetStorageInventoryWorker;

/** Poller is disabled unless farabi.storage.inventory.poll-interval is explicitly configured. */
@ApplicationScoped
public class DatasetStorageInventoryScheduler {
    private static final Logger LOG = Logger.getLogger(DatasetStorageInventoryScheduler.class);
    @Inject DatasetStorageInventoryWorker worker;

    @Scheduled(every = "${farabi.storage.inventory.poll-interval:off}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void poll() {
        try {
            for (int i = 0; i < 10 && worker.runOne(); i++) { /* bounded batch */ }
        } catch (RuntimeException failure) {
            LOG.error("Storage inventory worker poll failed", failure);
        }
    }
}
