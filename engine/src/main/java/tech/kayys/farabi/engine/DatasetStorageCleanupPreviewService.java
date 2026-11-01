package tech.kayys.farabi.engine;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import tech.kayys.farabi.engine.DatasetStorageInventoryPorts.DatasetReferences;
import tech.kayys.farabi.engine.DatasetStorageInventoryPorts.Inventory;
import tech.kayys.farabi.engine.DatasetStorageInventoryPorts.ObjectEntry;
import tech.kayys.farabi.engine.DatasetStorageInventoryPorts.Reports;
import tech.kayys.farabi.engine.DatasetStorageInventoryService.Finding;
import tech.kayys.farabi.engine.DatasetStorageInventoryService.FindingType;
import tech.kayys.farabi.engine.DatasetStorageInventoryService.Report;

/**
 * Creates an expiring, persisted dry-run proposal for orphan objects.
 * This service has deliberately no delete/apply port.
 */
public final class DatasetStorageCleanupPreviewService {
    public record Candidate(String locationRef, long sizeBytes, Instant lastModified) {
        public Candidate {
            if (locationRef == null || locationRef.isBlank() || sizeBytes < 0 || lastModified == null)
                throw new IllegalArgumentException("invalid cleanup candidate");
        }
    }
    public record Plan(UUID id, String workspaceId, UUID sourceReportId, String createdBy,
            Instant createdAt, Instant expiresAt, List<Candidate> candidates) {
        public Plan {
            Objects.requireNonNull(id); Objects.requireNonNull(sourceReportId);
            QueryContracts.required(workspaceId, "workspaceId"); QueryContracts.required(createdBy, "createdBy");
            Objects.requireNonNull(createdAt); Objects.requireNonNull(expiresAt);
            if (!expiresAt.isAfter(createdAt)) throw new IllegalArgumentException("plan expiry must follow creation");
            candidates = List.copyOf(candidates);
        }
    }
    public interface Plans {
        void save(Plan plan);
        Optional<Plan> find(UUID planId, String workspaceId);
        List<Plan> recent(String workspaceId, int limit);
    }

    private final Reports reports;
    private final DatasetReferences references;
    private final Inventory inventory;
    private final Plans plans;
    private final Clock clock;
    private final Duration planLifetime;

    public DatasetStorageCleanupPreviewService(Reports reports, DatasetReferences references, Inventory inventory,
            Plans plans, Clock clock, Duration planLifetime) {
        this.reports=Objects.requireNonNull(reports); this.references=Objects.requireNonNull(references);
        this.inventory=Objects.requireNonNull(inventory); this.plans=Objects.requireNonNull(plans);
        this.clock=Objects.requireNonNull(clock); this.planLifetime=Objects.requireNonNull(planLifetime);
        if (planLifetime.isNegative() || planLifetime.isZero()) throw new IllegalArgumentException("plan lifetime must be positive");
    }

    /** Revalidates every reported orphan against current catalog references and provider metadata. */
    public Plan preview(String workspaceId, UUID reportId, String actorId) {
        QueryContracts.required(workspaceId, "workspaceId"); QueryContracts.required(actorId, "actorId");
        Report report=reports.find(reportId, workspaceId)
                .orElseThrow(() -> new IllegalArgumentException("inventory report not found in workspace"));
        Instant now=clock.instant();
        var activeRefs=references.listActive(workspaceId).stream().map(r -> r.locationRef()).collect(java.util.stream.Collectors.toSet());
        List<Candidate> candidates=new ArrayList<>();
        Instant cutoff=now.minus(report.orphanGracePeriod());
        for (Finding finding:report.findings()) {
            if (finding.type()!=FindingType.ORPHAN_OBJECT || activeRefs.contains(finding.locationRef())) continue;
            Optional<ObjectEntry> current=inventory.inspect(finding.locationRef(), workspaceId);
            if (current.isEmpty()) continue;
            ObjectEntry object=current.get();
            if (object.sizeBytes()!=finding.actualBytes() || object.lastModified().isAfter(cutoff)) continue;
            candidates.add(new Candidate(object.locationRef(), object.sizeBytes(), object.lastModified()));
        }
        Plan plan=new Plan(UUID.randomUUID(), workspaceId, report.id(), actorId, now, now.plus(planLifetime), candidates);
        plans.save(plan);
        return plan;
    }

    public Optional<Plan> find(UUID id, String workspaceId) { return plans.find(id, workspaceId); }
    public List<Plan> recent(String workspaceId, int limit) {
        QueryContracts.required(workspaceId, "workspaceId");
        if (limit<1 || limit>100) throw new IllegalArgumentException("limit must be between 1 and 100");
        return plans.recent(workspaceId, limit);
    }
}
