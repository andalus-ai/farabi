package tech.kayys.farabi.engine;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import tech.kayys.farabi.engine.DatasetStorageReconciliationPorts.Report;

/** Creates immutable, read-only proposals to reconcile workspace quota counters. */
public final class DatasetStorageQuotaRepairPreviewService {
    public record Plan(UUID id, String workspaceId, String createdBy, Instant createdAt, Instant expiresAt,
            UUID reconciliationReportId, long quotaBytes,
            long ledgerUsedBytes, long proposedUsedBytes, long usedDeltaBytes,
            long ledgerReservedBytes, long proposedReservedBytes, long reservedDeltaBytes,
            long committedReservationBytes, boolean applicable) {
        public Plan {
            Objects.requireNonNull(id); Objects.requireNonNull(createdAt); Objects.requireNonNull(expiresAt);
            Objects.requireNonNull(reconciliationReportId);
            if (workspaceId == null || workspaceId.isBlank() || createdBy == null || createdBy.isBlank()
                    || expiresAt.isBefore(createdAt) || quotaBytes < 0 || ledgerUsedBytes < 0
                    || proposedUsedBytes < 0 || ledgerReservedBytes < 0 || proposedReservedBytes < 0
                    || committedReservationBytes < 0) throw new IllegalArgumentException("invalid quota repair plan");
        }
    }
    public interface Plans {
        void save(Plan plan);
        Optional<Plan> find(UUID id, String workspaceId);
        List<Plan> recent(String workspaceId, int limit);
    }

    private final DatasetStorageReconciliationService reconciliation;
    private final Plans plans;
    private final Clock clock;
    private final Duration lifetime;

    public DatasetStorageQuotaRepairPreviewService(DatasetStorageReconciliationService reconciliation,
            Plans plans, Clock clock, Duration lifetime) {
        this.reconciliation = Objects.requireNonNull(reconciliation);
        this.plans = Objects.requireNonNull(plans);
        this.clock = Objects.requireNonNull(clock);
        this.lifetime = Objects.requireNonNull(lifetime);
        if (lifetime.isNegative() || lifetime.isZero() || lifetime.compareTo(Duration.ofHours(1)) > 0)
            throw new IllegalArgumentException("preview lifetime must be positive and at most one hour");
    }

    public Plan preview(String workspaceId, String actorId) {
        required(workspaceId, "workspaceId"); required(actorId, "actorId");
        Report report = reconciliation.inspect(workspaceId);
        Instant now = clock.instant();
        long usedDelta = Math.subtractExact(report.observedUsedBytes(), report.ledgerUsedBytes());
        long reservedDelta = Math.subtractExact(report.observedReservedBytes(), report.ledgerReservedBytes());
        boolean fitsQuota = Math.addExact(report.observedUsedBytes(), report.observedReservedBytes()) <= report.quotaBytes();
        // Committed reservation mismatch is a separate integrity signal; never silently fold it into used bytes.
        boolean applicable = fitsQuota && report.committedReservationBytes() == report.observedUsedBytes();
        Plan plan = new Plan(UUID.randomUUID(), workspaceId, actorId, now, now.plus(lifetime), report.id(),
                report.quotaBytes(), report.ledgerUsedBytes(), report.observedUsedBytes(), usedDelta,
                report.ledgerReservedBytes(), report.observedReservedBytes(), reservedDelta,
                report.committedReservationBytes(), applicable);
        plans.save(plan);
        return plan;
    }

    public Optional<Plan> find(UUID id, String workspaceId) {
        Objects.requireNonNull(id); required(workspaceId, "workspaceId");
        return plans.find(id, workspaceId);
    }
    public List<Plan> recent(String workspaceId, int limit) {
        required(workspaceId, "workspaceId");
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
        return plans.recent(workspaceId, limit);
    }
    private static void required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " required");
    }
}