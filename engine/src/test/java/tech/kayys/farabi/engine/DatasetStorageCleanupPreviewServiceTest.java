package tech.kayys.farabi.engine;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.DatasetStorageCleanupPreviewService.*;
import tech.kayys.farabi.engine.DatasetStorageInventoryPorts.*;
import tech.kayys.farabi.engine.DatasetStorageInventoryService.*;

class DatasetStorageCleanupPreviewServiceTest {
    private static final Instant NOW=Instant.parse("2026-11-01T00:00:00Z");

    @Test void previewRevalidatesOrphansAndPersistsOnlyStillEligibleObjects() {
        UUID reportId=UUID.randomUUID();
        Report report=new Report(reportId,"workspace-a",NOW.minus(Duration.ofHours(2)),Duration.ofHours(1),
                1,4,List.of(
                    new Finding(FindingType.ORPHAN_OBJECT,"orphan-a",0,100,NOW.minus(Duration.ofHours(3))),
                    new Finding(FindingType.ORPHAN_OBJECT,"became-referenced",0,200,NOW.minus(Duration.ofHours(3))),
                    new Finding(FindingType.ORPHAN_OBJECT,"changed-size",0,300,NOW.minus(Duration.ofHours(3))),
                    new Finding(FindingType.ORPHAN_OBJECT,"too-young",0,400,NOW.minus(Duration.ofMinutes(10)))));
        Reports reports=new Reports() {
            public void save(Report ignored) {}
            public List<Report> recent(String w,int l) { return List.of(); }
            public Optional<Report> find(UUID id,String w) { return id.equals(reportId)&&w.equals("workspace-a")?Optional.of(report):Optional.empty(); }
        };
        DatasetReferences references=w -> List.of(new DatasetReference("became-referenced",200));
        Inventory inventory=new Inventory() {
            public Optional<ObjectEntry> inspect(String ref,String w) {
                if(ref.equals("orphan-a")) return Optional.of(new ObjectEntry(ref,100,NOW.minus(Duration.ofHours(3))));
                if(ref.equals("became-referenced")) return Optional.of(new ObjectEntry(ref,200,NOW.minus(Duration.ofHours(3))));
                if(ref.equals("changed-size")) return Optional.of(new ObjectEntry(ref,301,NOW.minus(Duration.ofHours(3))));
                if(ref.equals("too-young")) return Optional.of(new ObjectEntry(ref,400,NOW.minus(Duration.ofMinutes(10))));
                return Optional.empty();
            }
            public List<ObjectEntry> list(String w) { return List.of(); }
        };
        final Plan[] persisted=new Plan[1];
        Plans plans=new Plans() {
            public void save(Plan p) { persisted[0]=p; }
            public Optional<Plan> find(UUID id,String w) { return Optional.empty(); }
            public List<Plan> recent(String w,int l) { return List.of(); }
        };
        var service=new DatasetStorageCleanupPreviewService(reports,references,inventory,plans,
                Clock.fixed(NOW,ZoneOffset.UTC),Duration.ofMinutes(15));

        Plan plan=service.preview("workspace-a",reportId,"operator-1");
        assertSame(plan,persisted[0]);
        assertEquals("operator-1",plan.createdBy());
        assertEquals(NOW.plus(Duration.ofMinutes(15)),plan.expiresAt());
        assertEquals(1,plan.candidates().size());
        assertEquals("orphan-a",plan.candidates().get(0).locationRef());
    }

    @Test void refusesReportFromAnotherWorkspace() {
        Reports reports=new Reports() {
            public void save(Report ignored) {}
            public List<Report> recent(String w,int l) { return List.of(); }
            public Optional<Report> find(UUID id,String w) { return Optional.empty(); }
        };
        var service=new DatasetStorageCleanupPreviewService(reports,w -> List.of(),
                new Inventory() {
                    public Optional<ObjectEntry> inspect(String r,String w) { return Optional.empty(); }
                    public List<ObjectEntry> list(String w) { return List.of(); }
                },new Plans() {
                    public void save(Plan p) {}
                    public Optional<Plan> find(UUID id,String w) { return Optional.empty(); }
                    public List<Plan> recent(String w,int l) { return List.of(); }
                },Clock.fixed(NOW,ZoneOffset.UTC),Duration.ofMinutes(15));
        assertThrows(IllegalArgumentException.class,()->service.preview("workspace-b",UUID.randomUUID(),"operator"));
    }
}
