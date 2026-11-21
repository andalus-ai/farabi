package tech.kayys.farabi.engine;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.DatasetStorageReconciliationPorts.*;
import tech.kayys.farabi.engine.DatasetStorageQuotaRepairPreviewService.*;

class DatasetStorageQuotaRepairPreviewServiceTest {
    @Test void proposesIndependentUsedAndReservedAdjustmentsWithoutApplyingThem() {
        Instant now=Instant.parse("2026-11-01T12:00:00Z");
        Report report=new Report(UUID.randomUUID(),"ws-a",now,10_000,500,700,100,150,700,
                List.of(new Finding(FindingType.USED_BYTES_MISMATCH,500,700,"used mismatch")));
        DatasetStorageReconciliationService reconciliation=new DatasetStorageReconciliationService(
                new Store() {
                    public Report inspect(String workspace,Instant at) { assertEquals("ws-a",workspace); return report; }
                    public List<Report> recent(String workspace,int limit) { return List.of(report); }
                },Clock.fixed(now,ZoneOffset.UTC));
        InMemoryPlans plans=new InMemoryPlans();
        DatasetStorageQuotaRepairPreviewService service=new DatasetStorageQuotaRepairPreviewService(
                reconciliation,plans,Clock.fixed(now,ZoneOffset.UTC),Duration.ofMinutes(15));

        Plan plan=service.preview("ws-a","operator-1");

        assertEquals(700,plan.proposedUsedBytes());
        assertEquals(200,plan.usedDeltaBytes());
        assertEquals(150,plan.proposedReservedBytes());
        assertEquals(50,plan.reservedDeltaBytes());
        assertTrue(plan.applicable());
        assertEquals(now.plus(Duration.ofMinutes(15)),plan.expiresAt());
        assertEquals(plan,service.find(plan.id(),"ws-a").orElseThrow());
        assertTrue(service.find(plan.id(),"ws-b").isEmpty());
    }

    @Test void marksProposalNotApplicableWhenTotalsExceedQuotaOrCommittedBytesDisagree() {
        Instant now=Instant.parse("2026-11-01T12:00:00Z");
        Report report=new Report(UUID.randomUUID(),"ws-a",now,800,400,700,50,150,650,List.of());
        DatasetStorageReconciliationService reconciliation=new DatasetStorageReconciliationService(
                new Store() {
                    public Report inspect(String workspace,Instant at) { return report; }
                    public List<Report> recent(String workspace,int limit) { return List.of(); }
                },Clock.fixed(now,ZoneOffset.UTC));
        DatasetStorageQuotaRepairPreviewService service=new DatasetStorageQuotaRepairPreviewService(
                reconciliation,new InMemoryPlans(),Clock.fixed(now,ZoneOffset.UTC),Duration.ofMinutes(5));

        assertFalse(service.preview("ws-a","operator-1").applicable());
    }

    private static final class InMemoryPlans implements Plans {
        private final Map<UUID,Plan> values=new HashMap<>();
        public void save(Plan plan) { values.put(plan.id(),plan); }
        public Optional<Plan> find(UUID id,String workspace) {
            return Optional.ofNullable(values.get(id)).filter(p->p.workspaceId().equals(workspace));
        }
        public List<Plan> recent(String workspace,int limit) {
            return values.values().stream().filter(p->p.workspaceId().equals(workspace)).limit(limit).toList();
        }
    }
}