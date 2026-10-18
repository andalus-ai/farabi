package tech.kayys.andalus.bi.query.jobs;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Pure recovery decision logic. No remote calls or mutations.
 * Callers must atomically claim a job lease before performing work.
 */
public final class QueryJobRecoveryPolicy {
    public enum Decision { NONE, RECONCILE_REMOTE, INVESTIGATE_SUBMISSION, EXPIRE }
    private final Duration submissionGrace;
    public QueryJobRecoveryPolicy(Duration submissionGrace) {
        if (submissionGrace == null || submissionGrace.isNegative() || submissionGrace.isZero()) {
            throw new IllegalArgumentException("submissionGrace must be positive");
        }
        this.submissionGrace = submissionGrace;
    }
    public Decision decide(QueryJob job, Instant now) {
        Objects.requireNonNull(job);
        Objects.requireNonNull(now);
        if (job.status() == QueryJob.Status.SUCCEEDED ||
            job.status() == QueryJob.Status.FAILED ||
            job.status() == QueryJob.Status.CANCELLED) return Decision.NONE;
        if (job.expired(now)) return Decision.EXPIRE;
        if (job.providerJobId() != null) return Decision.RECONCILE_REMOTE;
        if (job.status() == QueryJob.Status.PENDING &&
            !now.isBefore(job.createdAt().plus(submissionGrace))) {
            return Decision.INVESTIGATE_SUBMISSION;
        }
        return Decision.NONE;
    }
}
