package tech.kayys.farabi.engine;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import tech.kayys.farabi.engine.LiveDashboardRuntime.ChartDataSource;
import tech.kayys.farabi.engine.QueryContracts.*;
import tech.kayys.farabi.engine.QueryLifecycle.ResultPage;
import tech.kayys.farabi.engine.QueryPorts.*;
import tech.kayys.farabi.engine.StructuredQueryCompiler.Query;

/**
 * Runs chart queries through the existing durable stack (persist-before-execute, grants re-checked by the
 * {@link Authorizer}, workspace-scoped results) and waits for completion within a hard deadline.
 * Progress of the execution record is driven elsewhere (the polling worker); {@link Progress} is only the wait hook.
 * On timeout the remote job is cancelled and the chart fails; nothing is retried, since a retry could double-submit.
 */
public final class DurableChartDataSource implements ChartDataSource {
    @FunctionalInterface
    public interface Progress {
        /** Blocks briefly (or drives a poller in tests) before the next status check. */
        void await(UUID executionId, String workspaceId) throws InterruptedException;
    }

    public static final class QueryTimeoutException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        public QueryTimeoutException(String message) { super(message); }
    }

    private final Authorizer authorizer;
    private final Executor executor;
    private final ExecutionRepository executions;
    private final QueryLifecycleService lifecycle;
    private final Clock clock;
    private final Duration timeout;
    private final Progress progress;
    private final int maxRows;

    public DurableChartDataSource(Authorizer authorizer, Executor executor, ExecutionRepository executions,
                                  QueryLifecycleService lifecycle, Clock clock, Duration timeout, Progress progress, int maxRows) {
        this.authorizer = Objects.requireNonNull(authorizer);
        this.executor = Objects.requireNonNull(executor);
        this.executions = Objects.requireNonNull(executions);
        this.lifecycle = Objects.requireNonNull(lifecycle);
        this.clock = Objects.requireNonNull(clock);
        this.timeout = Objects.requireNonNull(timeout);
        this.progress = Objects.requireNonNull(progress);
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be positive");
        if (maxRows < 1 || maxRows > StructuredQueryCompiler.MAX_LIMIT) throw new IllegalArgumentException("maxRows out of range");
        this.maxRows = maxRows;
    }

    @Override public List<Map<String, Object>> run(String workspaceId, String subjectId, Query query, Plan plan) {
        // "dataset:measure" is the grant grammar PolicyAuthorizer already understands, so grants are enforced a second time here.
        Request request = new Request(workspaceId, subjectId, plan.sourceId(), query.datasetId() + ":" + query.measureId(), Map.of());
        DurableQueryService durable = new DurableQueryService(authorizer, r -> plan, executor, executions, clock);
        Execution submitted = durable.submit(request);
        Instant deadline = clock.instant().plus(timeout);
        while (true) {
            Execution current = durable.get(submitted.id(), workspaceId, subjectId);
            switch (current.status()) {
                case SUCCEEDED -> { return readAll(current.id(), workspaceId, subjectId); }
                case FAILED -> throw new IllegalStateException("chart query failed");
                case CANCELLED -> throw new IllegalStateException("chart query cancelled");
                default -> { }
            }
            if (!clock.instant().isBefore(deadline)) {
                try { lifecycle.cancel(current.id(), workspaceId, subjectId); } catch (RuntimeException ignored) { /* best effort */ }
                throw new QueryTimeoutException("chart query exceeded " + timeout);
            }
            try {
                progress.await(current.id(), workspaceId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for chart query", e);
            }
        }
    }

    private List<Map<String, Object>> readAll(UUID id, String ws, String subject) {
        List<Map<String, Object>> rows = new ArrayList<>();
        int offset = 0;
        while (true) {
            ResultPage page = lifecycle.results(id, ws, subject, offset, Math.min(1000, maxRows - rows.size() + 1));
            rows.addAll(page.rows());
            if (rows.size() > maxRows) throw new IllegalStateException("result exceeds row cap of " + maxRows);
            if (!page.hasMore() || page.rows().isEmpty()) return rows;
            offset += page.rows().size();
        }
    }
}
