package tech.kayys.farabi.engine;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.DatasetStorageQuotaRepairApplicationService.*;
import tech.kayys.farabi.engine.DatasetStorageQuotaRepairPreviewService.Plan;

class DatasetStorageQuotaRepairApplicationServiceTest {
    @Test void explicitApprovalAppliesOnceAndReturnsExistingApplicationOnRetry() {
        Instant now=Instant.parse("2026-11-02T10:00:00Z");
        Plan plan=new Plan(UUID.randomUUID(),"workspace-a","creator",now.minusSeconds(30),now.plusSeconds(300),
                UUID.randomUUID(),1000,500,600,100,100,150,50,600,true);
        DatasetStorageQuotaRepairPreviewService previews=previewService(plan);
        InMemoryApplications applications=new InMemoryApplications();
        DatasetStorageQuotaRepairApplicationService service=new DatasetStorageQuotaRepairApplicationService(
                previews,applications,Clock.fixed(now,ZoneOffset.UTC));

        Application first=service.approveAndApply(plan.id(),"workspace-a","approver");
        Application retry=service.approveAndApply(plan.id(),"workspace-a","different-approver");

        assertEquals(first,retry);
        assertEquals(1,applications.applyCount);
        assertEquals("approver",first.approvedBy());
        assertEquals(600,first.appliedUsedBytes());
        assertEquals(150,first.appliedReservedBytes());
    }

    @Test void rejectsExpiredAndInapplicablePlansBeforeCallingStore() {
        Instant now=Instant.parse("2026-11-02T10:00:00Z");
        Plan expired=new Plan(UUID.randomUUID(),"workspace-a","creator",now.minusSeconds(600),now.minusSeconds(1),
                UUID.randomUUID(),1000,500,600,100,100,150,50,600,true);
        Plan unsafe=new Plan(UUID.randomUUID(),"workspace-a","creator",now.minusSeconds(30),now.plusSeconds(300),
                UUID.randomUUID(),1000,500,600,100,100,150,50,999,false);
        InMemoryApplications applications=new InMemoryApplications();
        var service=new DatasetStorageQuotaRepairApplicationService(previewService(expired,unsafe),
                applications,Clock.fixed(now,ZoneOffset.UTC));

        assertThrows(StalePreviewException.class,()->service.approveAndApply(expired.id(),"workspace-a","operator"));
        assertThrows(InapplicablePreviewException.class,()->service.approveAndApply(unsafe.id(),"workspace-a","operator"));
        assertEquals(0,applications.applyCount);
    }

    private static DatasetStorageQuotaRepairPreviewService previewService(Plan... values) {
        Map<UUID,Plan> plans=new HashMap<>();
        for(Plan p:values)plans.put(p.id(),p);
        var reports=new DatasetStorageReconciliationPorts.Store() {
            public DatasetStorageReconciliationPorts.Report inspect(String w,Instant at){throw new UnsupportedOperationException();}
            public List<DatasetStorageReconciliationPorts.Report> recent(String w,int limit){return List.of();}
        };
        var reconciliation=new DatasetStorageReconciliationService(reports,Clock.systemUTC());
        return new DatasetStorageQuotaRepairPreviewService(reconciliation,new DatasetStorageQuotaRepairPreviewService.Plans() {
            public void save(Plan p){plans.put(p.id(),p);}
            public Optional<Plan> find(UUID id,String workspace){return Optional.ofNullable(plans.get(id)).filter(p->p.workspaceId().equals(workspace));}
            public List<Plan> recent(String workspace,int limit){return plans.values().stream().filter(p->p.workspaceId().equals(workspace)).limit(limit).toList();}
        },Clock.systemUTC(),Duration.ofMinutes(15));
    }

    private static final class InMemoryApplications implements Applications {
        private final Map<UUID,Application> applied=new HashMap<>();
        int applyCount;
        public Application apply(Plan p,String actor,Instant now) {
            applyCount++;
            Application a=new Application(UUID.randomUUID(),p.id(),p.workspaceId(),actor,now,
                    p.ledgerUsedBytes(),p.proposedUsedBytes(),p.ledgerReservedBytes(),p.proposedReservedBytes());
            applied.put(p.id(),a);return a;
        }
        public Optional<Application> findByPreview(UUID id,String workspace) {
            return Optional.ofNullable(applied.get(id)).filter(a->a.workspaceId().equals(workspace));
        }
    }
}