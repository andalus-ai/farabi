package tech.kayys.farabi.engine;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import tech.kayys.farabi.engine.DatasetStorageInventoryPorts.DatasetReference;
import tech.kayys.farabi.engine.DatasetStorageInventoryPorts.DatasetReferences;
import tech.kayys.farabi.engine.DatasetStorageInventoryPorts.Inventory;
import tech.kayys.farabi.engine.DatasetStorageInventoryPorts.ObjectEntry;
import tech.kayys.farabi.engine.DatasetStorageInventoryPorts.Reports;

/** Compares catalog references with provider inventory; detection only, never deletes or repairs. */
public final class DatasetStorageInventoryService {
    public enum FindingType { MISSING_OBJECT, SIZE_MISMATCH, ORPHAN_OBJECT }
    public record Finding(FindingType type, String locationRef, long expectedBytes, long actualBytes,
            Instant objectLastModified) {
        public Finding {
            Objects.requireNonNull(type);
            if (locationRef == null || locationRef.isBlank() || expectedBytes < 0 || actualBytes < 0)
                throw new IllegalArgumentException("invalid inventory finding");
        }
    }
    public record Report(UUID id, String workspaceId, Instant inspectedAt, Duration orphanGracePeriod,
            int catalogObjects, int providerObjects, List<Finding> findings) {
        public Report {
            Objects.requireNonNull(id);
            if (workspaceId == null || workspaceId.isBlank()) throw new IllegalArgumentException("workspace required");
            Objects.requireNonNull(inspectedAt); Objects.requireNonNull(orphanGracePeriod);
            if (orphanGracePeriod.isNegative() || catalogObjects < 0 || providerObjects < 0)
                throw new IllegalArgumentException("invalid inventory report");
            findings = List.copyOf(findings);
        }
        public boolean consistent() { return findings.isEmpty(); }
    }

    private final DatasetReferences references;
    private final Inventory inventory;
    private final Clock clock;
    private final Duration orphanGracePeriod;
    private final Reports reports;

    public DatasetStorageInventoryService(DatasetReferences references, Inventory inventory,
            Clock clock, Duration orphanGracePeriod) {
        this(references, inventory, clock, orphanGracePeriod, null);
    }

    public DatasetStorageInventoryService(DatasetReferences references, Inventory inventory,
            Clock clock, Duration orphanGracePeriod, Reports reports) {
        this.references = Objects.requireNonNull(references);
        this.inventory = Objects.requireNonNull(inventory);
        this.clock = Objects.requireNonNull(clock);
        this.orphanGracePeriod = Objects.requireNonNull(orphanGracePeriod);
        if (orphanGracePeriod.isNegative()) throw new IllegalArgumentException("orphan grace period cannot be negative");
        this.reports = reports;
    }

    public List<Report> recent(String workspaceId, int limit) {
        if (workspaceId == null || workspaceId.isBlank()) throw new IllegalArgumentException("workspaceId required");
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
        if (reports == null) throw new IllegalStateException("inventory report persistence is not configured");
        return reports.recent(workspaceId, limit);
    }

    public Report inspect(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) throw new IllegalArgumentException("workspaceId required");
        Instant now = clock.instant();
        Map<String, DatasetReference> expected = new HashMap<>();
        for (DatasetReference ref : references.listActive(workspaceId)) {
            if (expected.put(ref.locationRef(), ref) != null)
                throw new IllegalStateException("duplicate dataset storage reference in workspace catalog");
        }
        Map<String, ObjectEntry> actual = new HashMap<>();
        for (ObjectEntry entry : inventory.list(workspaceId)) {
            if (actual.put(entry.locationRef(), entry) != null)
                throw new IllegalStateException("duplicate object reference in provider inventory");
        }
        List<Finding> findings = new ArrayList<>();
        for (DatasetReference ref : expected.values()) {
            ObjectEntry object = actual.get(ref.locationRef());
            if (object == null) {
                findings.add(new Finding(FindingType.MISSING_OBJECT, ref.locationRef(),
                        ref.expectedSizeBytes(), 0, null));
            } else if (object.sizeBytes() != ref.expectedSizeBytes()) {
                findings.add(new Finding(FindingType.SIZE_MISMATCH, ref.locationRef(),
                        ref.expectedSizeBytes(), object.sizeBytes(), object.lastModified()));
            }
        }
        Instant orphanCutoff = now.minus(orphanGracePeriod);
        for (ObjectEntry object : actual.values()) {
            if (!expected.containsKey(object.locationRef()) && !object.lastModified().isAfter(orphanCutoff)) {
                findings.add(new Finding(FindingType.ORPHAN_OBJECT, object.locationRef(),
                        0, object.sizeBytes(), object.lastModified()));
            }
        }
        Report report = new Report(UUID.randomUUID(), workspaceId, now, orphanGracePeriod, expected.size(), actual.size(), findings);
        if (reports != null) reports.save(report);
        return report;
    }
}
