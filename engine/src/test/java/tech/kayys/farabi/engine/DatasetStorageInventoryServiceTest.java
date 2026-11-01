package tech.kayys.farabi.engine;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.DatasetStorageInventoryPorts.*;

class DatasetStorageInventoryServiceTest {
    private final Instant now=Instant.parse("2026-11-01T00:00:00Z");

    @Test void detectsMissingSizeMismatchAndOnlyOldOrphans() {
        DatasetReferences refs=workspace -> List.of(
                new DatasetReference("expected/missing",10),
                new DatasetReference("expected/wrong-size",20),
                new DatasetReference("expected/ok",30));
        Inventory inventory=new Inventory() {
            public Optional<ObjectEntry> inspect(String ref,String workspace){return Optional.empty();}
            public List<ObjectEntry> list(String workspace){return List.of(
                    new ObjectEntry("expected/wrong-size",21,now.minus(Duration.ofHours(1))),
                    new ObjectEntry("expected/ok",30,now.minus(Duration.ofHours(1))),
                    new ObjectEntry("orphan/old",5,now.minus(Duration.ofHours(48))),
                    new ObjectEntry("orphan/new",7,now.minus(Duration.ofHours(1))));}
        };
        var service=new DatasetStorageInventoryService(refs,inventory,Clock.fixed(now,ZoneOffset.UTC),Duration.ofHours(24));
        var report=service.inspect("workspace-a");
        assertEquals(3,report.findings().size());
        assertTrue(report.findings().stream().anyMatch(f->f.type()==DatasetStorageInventoryService.FindingType.MISSING_OBJECT));
        assertTrue(report.findings().stream().anyMatch(f->f.type()==DatasetStorageInventoryService.FindingType.SIZE_MISMATCH));
        assertTrue(report.findings().stream().anyMatch(f->f.type()==DatasetStorageInventoryService.FindingType.ORPHAN_OBJECT
                && f.locationRef().equals("orphan/old")));
        assertFalse(report.findings().stream().anyMatch(f->f.locationRef().equals("orphan/new")));
    }

    @Test void rejectsDuplicateCatalogReferences() {
        DatasetReferences refs=workspace -> List.of(new DatasetReference("same",1),new DatasetReference("same",1));
        Inventory inventory=new Inventory() {
            public Optional<ObjectEntry> inspect(String ref,String workspace){return Optional.empty();}
            public List<ObjectEntry> list(String workspace){return List.of();}
        };
        var service=new DatasetStorageInventoryService(refs,inventory,Clock.fixed(now,ZoneOffset.UTC),Duration.ofDays(1));
        assertThrows(IllegalStateException.class,()->service.inspect("workspace-a"));
    }
}
