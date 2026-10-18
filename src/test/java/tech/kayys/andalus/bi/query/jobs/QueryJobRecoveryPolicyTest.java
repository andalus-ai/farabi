package tech.kayys.andalus.bi.query.jobs;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class QueryJobRecoveryPolicyTest {
    private static final Instant START = Instant.parse("2026-10-01T00:00:00Z");
    private QueryJob job(QueryJob.Status status, String providerId) {
        return new QueryJob(UUID.randomUUID(), "workspace", "actor", "source",
                providerId, status, START, START, START.plus(Duration.ofHours(1)));
    }

    @Test void staleSubmissionNeedsInvestigationNotBlindRetry() {
        var policy = new QueryJobRecoveryPolicy(Duration.ofMinutes(5));
        assertEquals(QueryJobRecoveryPolicy.Decision.NONE,
                policy.decide(job(QueryJob.Status.PENDING, null), START.plusSeconds(60)));
        assertEquals(QueryJobRecoveryPolicy.Decision.INVESTIGATE_SUBMISSION,
                policy.decide(job(QueryJob.Status.PENDING, null), START.plusSeconds(301)));
    }

    @Test void remoteJobsAreReconciledAndExpiredJobsAreNotPolled() {
        var policy = new QueryJobRecoveryPolicy(Duration.ofMinutes(5));
        assertEquals(QueryJobRecoveryPolicy.Decision.RECONCILE_REMOTE,
                policy.decide(job(QueryJob.Status.RUNNING, "remote_1"), START.plusSeconds(30)));
        assertEquals(QueryJobRecoveryPolicy.Decision.EXPIRE,
                policy.decide(job(QueryJob.Status.RUNNING, "remote_1"), START.plus(Duration.ofHours(2))));
        assertEquals(QueryJobRecoveryPolicy.Decision.NONE,
                policy.decide(job(QueryJob.Status.SUCCEEDED, "remote_1"), START.plus(Duration.ofHours(2))));
    }

    @Test void invalidGraceIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new QueryJobRecoveryPolicy(Duration.ZERO));
    }
}
