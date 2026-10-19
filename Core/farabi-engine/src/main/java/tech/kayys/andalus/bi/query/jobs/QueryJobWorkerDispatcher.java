package tech.kayys.andalus.bi.query.jobs;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Bounded single-pass worker dispatch. The handler must use the supplied
 * fenced lease for every database transition; remote side effects require
 * separate idempotency guarantees.
 */
public final class QueryJobWorkerDispatcher {
    public interface CandidateSource { Iterable<UUID> scan(int limit); }
    public interface LeaseClaimer {
        JdbcFencedJobWorkerStore.Lease claim(UUID id, String workerId, Duration ttl);
    }
    public record DispatchResult(int discovered, int claimed, int processed, int failed) {}

    private final CandidateSource candidates;
    private final LeaseClaimer leases;

    public QueryJobWorkerDispatcher(CandidateSource candidates, LeaseClaimer leases) {
        this.candidates = Objects.requireNonNull(candidates);
        this.leases = Objects.requireNonNull(leases);
    }

    /**
     * Processes at most maxCandidates. The caller supplies a trusted worker ID.
     * Failure is counted and processing continues; scheduling is external.
     */
    public DispatchResult runOnce(String workerId, Duration leaseTtl, int maxCandidates,
                                  Consumer<JdbcFencedJobWorkerStore.Lease> handler) {
        if (workerId == null || workerId.isBlank() || workerId.length() > 128)
            throw new IllegalArgumentException("invalid workerId");
        if (leaseTtl == null || leaseTtl.isNegative() || leaseTtl.isZero()
                || leaseTtl.compareTo(Duration.ofMinutes(10)) > 0)
            throw new IllegalArgumentException("invalid lease TTL");
        if (maxCandidates < 1 || maxCandidates > 500)
            throw new IllegalArgumentException("maxCandidates must be 1..500");
        Objects.requireNonNull(handler);
        int discovered = 0, claimed = 0, processed = 0, failed = 0;
        for (UUID id : candidates.scan(maxCandidates)) {
            if (discovered == maxCandidates) break;
            discovered++;
            try {
                var lease = leases.claim(id, workerId, leaseTtl);
                if (lease == null) continue;
                claimed++;
                handler.accept(lease);
                processed++;
            } catch (RuntimeException ex) {
                failed++;
            }
        }
        return new DispatchResult(discovered, claimed, processed, failed);
    }
}
