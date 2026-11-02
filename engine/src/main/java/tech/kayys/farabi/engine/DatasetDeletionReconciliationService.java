package tech.kayys.farabi.engine;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import tech.kayys.farabi.engine.DatasetStorageInventoryPorts.Inventory;

/** Read-only reconciliation of dataset deletion state, provider objects and workspace quota ledger. */
public final class DatasetDeletionReconciliationService {
    public enum FindingType {
        ARCHIVED_OBJECT_PRESENT, SUCCEEDED_DATASET_NOT_ARCHIVED, ARCHIVED_STORAGE_BYTES_NONZERO,
        LEDGER_USED_MISMATCH, LEDGER_RESERVED_MISMATCH
    }
    public record DatasetState(UUID datasetId,String locationRef,String lifecycle,long storageBytes) {}
    public record DeletionState(UUID jobId,UUID datasetId,String status) {}
    public record Snapshot(List<DatasetState> datasets,List<DeletionState> deletions,
            long ledgerUsedBytes,long ledgerReservedBytes,long expectedUsedBytes,long expectedReservedBytes) {
        public Snapshot { datasets=List.copyOf(datasets);deletions=List.copyOf(deletions); }
    }
    public record Finding(FindingType type,UUID datasetId,UUID deletionJobId,String locationRef,
            long expectedBytes,long actualBytes) {}
    public record Report(UUID id,String workspaceId,Instant inspectedAt,int datasetsInspected,
            int deletionJobsInspected,List<Finding> findings) {
        public Report { findings=List.copyOf(findings); }
        public boolean consistent(){return findings.isEmpty();}
    }
    public interface Repository {
        Snapshot snapshot(String workspaceId);
        void save(Report report);
        List<Report> recent(String workspaceId,int limit);
    }
    private final Repository repository;
    private final Inventory inventory;
    private final Clock clock;
    public DatasetDeletionReconciliationService(Repository repository,Inventory inventory,Clock clock){
        this.repository=Objects.requireNonNull(repository);this.inventory=Objects.requireNonNull(inventory);this.clock=Objects.requireNonNull(clock);
    }
    public Report inspect(String workspaceId){
        if(workspaceId==null||workspaceId.isBlank())throw new IllegalArgumentException("workspaceId required");
        Snapshot snapshot=repository.snapshot(workspaceId);
        List<Finding> findings=new ArrayList<>();
        for(DatasetState dataset:snapshot.datasets()){
            if("ARCHIVED".equals(dataset.lifecycle())){
                if(dataset.storageBytes()>0)findings.add(new Finding(FindingType.ARCHIVED_STORAGE_BYTES_NONZERO,dataset.datasetId(),null,null,0,dataset.storageBytes()));
                var object=inventory.inspect(dataset.locationRef(),workspaceId);
                if(object.isPresent())
                    findings.add(new Finding(FindingType.ARCHIVED_OBJECT_PRESENT,dataset.datasetId(),null,dataset.locationRef(),0,object.get().sizeBytes()));
            }
        }
        for(DeletionState deletion:snapshot.deletions()){
            if("SUCCEEDED".equals(deletion.status())){
                DatasetState ds=snapshot.datasets().stream().filter(d->d.datasetId().equals(deletion.datasetId())).findFirst().orElse(null);
                if(ds!=null&&!"ARCHIVED".equals(ds.lifecycle()))
                    findings.add(new Finding(FindingType.SUCCEEDED_DATASET_NOT_ARCHIVED,ds.datasetId(),deletion.jobId(),null,0,0));
            }
        }
        if(snapshot.ledgerUsedBytes()!=snapshot.expectedUsedBytes())
            findings.add(new Finding(FindingType.LEDGER_USED_MISMATCH,null,null,null,snapshot.expectedUsedBytes(),snapshot.ledgerUsedBytes()));
        if(snapshot.ledgerReservedBytes()!=snapshot.expectedReservedBytes())
            findings.add(new Finding(FindingType.LEDGER_RESERVED_MISMATCH,null,null,null,snapshot.expectedReservedBytes(),snapshot.ledgerReservedBytes()));
        Report report=new Report(UUID.randomUUID(),workspaceId,clock.instant(),snapshot.datasets().size(),snapshot.deletions().size(),findings);
        repository.save(report);
        return report;
    }
    public List<Report> recent(String workspaceId,int limit){
        if(workspaceId==null||workspaceId.isBlank())throw new IllegalArgumentException("workspaceId required");
        if(limit<1||limit>100)throw new IllegalArgumentException("limit must be 1..100");
        return repository.recent(workspaceId,limit);
    }
}
