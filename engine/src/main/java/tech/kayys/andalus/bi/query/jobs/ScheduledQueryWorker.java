package tech.kayys.andalus.bi.query.jobs;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Single-process fixed-delay scheduling adapter for the bounded dispatcher.
 * This is not a distributed scheduler: distributed exclusion is enforced by
 * the database lease claim inside the dispatcher.
 */
public final class ScheduledQueryWorker implements AutoCloseable {
    private final QueryJobWorkerDispatcher dispatcher;
    private final Consumer<JdbcFencedJobWorkerStore.Lease> handler;
    private final String workerId;
    private final Duration leaseTtl;
    private final int batchSize;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean();
    private ScheduledFuture<?> scheduled;

    public ScheduledQueryWorker(QueryJobWorkerDispatcher dispatcher,
                                Consumer<JdbcFencedJobWorkerStore.Lease> handler,
                                String workerId, Duration leaseTtl, int batchSize) {
        this.dispatcher = Objects.requireNonNull(dispatcher);
        this.handler = Objects.requireNonNull(handler);
        if (workerId == null || workerId.isBlank() || workerId.length() > 128)
            throw new IllegalArgumentException("invalid workerId");
        if (leaseTtl == null || leaseTtl.isZero() || leaseTtl.isNegative()
                || leaseTtl.compareTo(Duration.ofMinutes(10)) > 0)
            throw new IllegalArgumentException("invalid leaseTtl");
        if (batchSize < 1 || batchSize > 500) throw new IllegalArgumentException("invalid batchSize");
        this.workerId = workerId;
        this.leaseTtl = leaseTtl;
        this.batchSize = batchSize;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "andalus-bi-query-worker");
            t.setDaemon(true);
            return t;
        });
    }

    /** Starts one fixed-delay poller; duplicate starts are rejected. */
    public synchronized void start(Duration interval) {
        if (interval == null || interval.isZero() || interval.isNegative()
                || interval.compareTo(Duration.ofHours(1)) > 0)
            throw new IllegalArgumentException("interval must be positive and <= 1 hour");
        if (executor.isShutdown()) throw new IllegalStateException("worker closed");
        if (scheduled != null) throw new IllegalStateException("worker already started");
        long delayMillis = Math.max(1, interval.toMillis());
        scheduled = executor.scheduleWithFixedDelay(this::pollSafely, 0, delayMillis, TimeUnit.MILLISECONDS);
    }

    private void pollSafely() {
        if (!running.compareAndSet(false, true)) return;
        try {
            dispatcher.runOnce(workerId, leaseTtl, batchSize, handler);
        } catch (RuntimeException ex) {
            // Keep scheduled polling alive. Wire production logging and metrics
            // at the deployment boundary; never log query text or credentials.
        } finally {
            running.set(false);
        }
    }

    @Override public synchronized void close() {
        if (scheduled != null) scheduled.cancel(false);
        executor.shutdown();
    }
}
