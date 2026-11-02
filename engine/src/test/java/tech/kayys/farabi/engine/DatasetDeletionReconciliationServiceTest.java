package tech.kayys.farabi.engine;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.DatasetDeletionReconciliationService.*;
import tech.kayys.farabi.engine.DatasetStorageInventoryPorts.Inventory;
import tech.kayys.farabi.engine.DatasetStorageInventoryPorts.ObjectEntry;

class DatasetDeletionReconciliationServiceTest {
    private final Instant now=Instant.parse("2026-11-02T12:00:00Z");

    @Test void reportsArchivedObjectsSucceededJobsAndLedgerDriftWithoutMutatingState(){
        UUID archived=UUID.randomUUID(), active=UUID.randomUUID(), job=UUID.randomUUID();
        DatasetState archivedDataset=new DatasetState(archived,"object://archived","ARCHIVED",128);
        DatasetState activeDataset=new DatasetState(active,"object://active","DELETE_PENDING",256);
        Snapshot snapshot=new Snapshot(List.of(archivedDataset,activeDataset),
                List.of(new DeletionState(job,active,"SUCCEEDED")),400,50,256,20);
        RecordingRepository repository=new RecordingRepository(snapshot);
        Inventory inventory=new Inventory(){
            public Optional<ObjectEntry> inspect(String ref,String workspace){
                return ref.equals("object://archived")
                        ?Optional.of(new ObjectEntry(ref,128,now)):Optional.empty();
            }
            public List<ObjectEntry> list(String workspace){return List.of();}
        };
        var service=new DatasetDeletionReconciliationService(repository,inventory,Clock.fixed(now,ZoneOffset.UTC));

        Report report=service.inspect("workspace-a","operator-a");

        assertFalse(report.consistent());
        assertEquals(5,report.findings().size());
        assertTrue(report.findings().stream().anyMatch(f->f.type()==FindingType.ARCHIVED_OBJECT_PRESENT&&f.actualBytes()==128));
        assertTrue(report.findings().stream().anyMatch(f->f.type()==FindingType.SUCCEEDED_DATASET_NOT_ARCHIVED));
        assertTrue(report.findings().stream().anyMatch(f->f.type()==FindingType.ARCHIVED_STORAGE_BYTES_NONZERO));
        assertTrue(report.findings().stream().anyMatch(f->f.type()==FindingType.LEDGER_USED_MISMATCH));
        assertTrue(report.findings().stream().anyMatch(f->f.type()==FindingType.LEDGER_RESERVED_MISMATCH));
        assertSame(report,repository.saved);
    }

    @Test void cleanSnapshotProducesConsistentReport(){
        RecordingRepository repository=new RecordingRepository(new Snapshot(List.of(),List.of(),0,0,0,0));
        Inventory inventory=new Inventory(){
            public Optional<ObjectEntry> inspect(String ref,String workspace){return Optional.empty();}
            public List<ObjectEntry> list(String workspace){return List.of();}
        };
        Report report=new DatasetDeletionReconciliationService(repository,inventory,Clock.fixed(now,ZoneOffset.UTC)).inspect("workspace-a");
        assertTrue(report.consistent());
        assertTrue(report.findings().isEmpty());
    }

    private static final class RecordingRepository implements Repository {
        final Snapshot snapshot;Report saved;
        RecordingRepository(Snapshot snapshot){this.snapshot=snapshot;}
        public Snapshot snapshot(String workspace){return snapshot;}
        public void save(Report report){saved=report;}
        public List<Report> recent(String workspace,int limit){return saved==null?List.of():List.of(saved);}
    }
}
