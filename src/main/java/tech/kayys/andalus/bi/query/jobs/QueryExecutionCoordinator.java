package tech.kayys.andalus.bi.query.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import tech.kayys.andalus.bi.query.SemanticQuery;
import tech.kayys.andalus.bi.security.AccessContext;
import tech.kayys.andalus.bi.toba.GovernedDremioQueryService;
import tech.kayys.andalus.bi.toba.DremioHttpGateway;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * Server-side orchestration for governed query submission and owner-scoped reads.
 * Never expose transition methods or downstream job IDs to untrusted clients.
 */
public final class QueryExecutionCoordinator {
    private final QueryJobService jobs;
    private final GovernedDremioQueryService governed;
    private final DremioHttpGateway dremio;
    private final GovernedDremioQueryService.DatasetPolicy policy;

    public QueryExecutionCoordinator(QueryJobService jobs, GovernedDremioQueryService governed,
                                     DremioHttpGateway dremio,
                                     GovernedDremioQueryService.DatasetPolicy policy) {
        this.jobs = Objects.requireNonNull(jobs);
        this.governed = Objects.requireNonNull(governed);
        this.dremio = Objects.requireNonNull(dremio);
        this.policy = Objects.requireNonNull(policy);
    }

    public QueryJob submit(AccessContext actor, String workspaceId, SemanticQuery query, Duration ttl) {
        Objects.requireNonNull(query);
        // Deny before persisting a job, while the governed service rechecks
        // authorization immediately before downstream execution.
        Objects.requireNonNull(actor).requireWorkspace(workspaceId);
        if (!policy.permits(actor, workspaceId, query.sourceId())) {
            throw new tech.kayys.andalus.bi.security.AccessDeniedException("dataset access denied");
        }
        QueryJob job = jobs.create(actor, workspaceId, query.sourceId(), ttl);
        try {
            String providerId = governed.submit(actor, workspaceId, query);
            return jobs.transition(actor, workspaceId, job.id(),
                    QueryJob.Status.PENDING, QueryJob.Status.SUBMITTED, providerId);
        } catch (RuntimeException e) {
            // A downstream submission may have succeeded before a network error;
            // recovery/reconciliation is required to prevent orphaned provider jobs.
            try {
                jobs.transition(actor, workspaceId, job.id(),
                        QueryJob.Status.PENDING, QueryJob.Status.FAILED, null);
            } catch (RuntimeException transitionFailure) {
                e.addSuppressed(transitionFailure);
            }
            throw e;
        }
    }

    public QueryJob get(AccessContext actor, String workspaceId, UUID jobId) {
        return jobs.get(actor, workspaceId, jobId);
    }

    public JsonNode status(AccessContext actor, String workspaceId, UUID jobId) {
        QueryJob job = authorizedProviderJob(actor, workspaceId, jobId);
        return dremio.status(job.providerJobId());
    }

    public JsonNode results(AccessContext actor, String workspaceId, UUID jobId,
                            int offset, int limit) {
        QueryJob job = authorizedProviderJob(actor, workspaceId, jobId);
        if (job.status() != QueryJob.Status.SUCCEEDED) {
            throw new IllegalStateException("job results unavailable before successful completion");
        }
        return dremio.results(job.providerJobId(), offset, limit);
    }

    /**
     * Worker-only reconciliation. Call from a trusted backend process, never
     * directly from a user-controlled status response or HTTP request body.
     */
    public QueryJob reconcile(AccessContext actor, String workspaceId, UUID jobId,
                              QueryJob.Status next) {
        QueryJob job = authorizedProviderJob(actor, workspaceId, jobId);
        JsonNode remote = dremio.status(job.providerJobId());
        String state = remote.path("jobState").asText("");
        QueryJob.Status observed = switch (state) {
            case "RUNNING" -> QueryJob.Status.RUNNING;
            case "COMPLETED" -> QueryJob.Status.SUCCEEDED;
            case "FAILED" -> QueryJob.Status.FAILED;
            case "CANCELED", "CANCELLED" -> QueryJob.Status.CANCELLED;
            default -> null;
        };
        if (observed == null || observed != next) {
            throw new IllegalStateException("Dremio status does not match requested transition");
        }
        // Some jobs complete before first poll; advance through RUNNING to
        // preserve the persisted state machine.
        if (job.status() == QueryJob.Status.SUBMITTED
                && (next == QueryJob.Status.SUCCEEDED || next == QueryJob.Status.FAILED
                    || next == QueryJob.Status.CANCELLED)) {
            if (next == QueryJob.Status.SUCCEEDED) {
                job = jobs.transition(actor, workspaceId, jobId,
                        QueryJob.Status.SUBMITTED, QueryJob.Status.RUNNING, null);
            }
        }
        return jobs.transition(actor, workspaceId, jobId, job.status(), next, null);
    }

    private QueryJob authorizedProviderJob(AccessContext actor, String workspaceId, UUID jobId) {
        QueryJob job = jobs.get(actor, workspaceId, jobId);
        if (!policy.permits(actor, workspaceId, job.sourceId())) {
            throw new tech.kayys.andalus.bi.security.AccessDeniedException("dataset access denied");
        }
        if (job.providerJobId() == null) throw new IllegalStateException("job not yet submitted");
        return job;
    }
}
