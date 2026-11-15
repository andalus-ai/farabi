package tech.kayys.farabi.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.DatasetStorageInventoryAuditPorts.EventType;

class JdbcDatasetStorageInventoryAuditLogTest {
    private JdbcDataSource source;
    private JdbcDatasetStorageInventoryAuditLog audit;

    @BeforeEach
    void setUp() throws Exception {
        source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:audit-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (var c = source.getConnection(); var s = c.createStatement()) {
            s.execute("""
                    CREATE TABLE farabi_storage_inventory_audit (
                        id UUID PRIMARY KEY, workspace_id VARCHAR(200) NOT NULL, job_id UUID NOT NULL,
                        actor_id VARCHAR(300) NOT NULL, event_type VARCHAR(30) NOT NULL,
                        attempt INTEGER NOT NULL, report_id UUID, error_code VARCHAR(100),
                        occurred_at TIMESTAMP NOT NULL)
                    """);
        }
        audit = new JdbcDatasetStorageInventoryAuditLog(source);
    }

    @Test
    void readsOnlyTheRequestedWorkspaceAndJobInNewestFirstOrder() throws Exception {
        UUID jobA = UUID.randomUUID();
        UUID jobB = UUID.randomUUID();
        insert("workspace-a", jobA, EventType.SUBMITTED, Instant.parse("2026-11-14T00:00:00Z"));
        insert("workspace-a", jobA, EventType.SUCCEEDED, Instant.parse("2026-11-14T00:02:00Z"));
        insert("workspace-a", jobB, EventType.FAILED, Instant.parse("2026-11-14T00:03:00Z"));
        insert("workspace-b", jobA, EventType.FAILED, Instant.parse("2026-11-14T00:04:00Z"));

        var all = audit.recent("workspace-a", null, 10);
        assertEquals(3, all.size());
        assertEquals(EventType.FAILED, all.get(0).type());
        assertEquals(EventType.SUCCEEDED, all.get(1).type());

        var oneJob = audit.recent("workspace-a", jobA, 10);
        assertEquals(2, oneJob.size());
        assertTrue(oneJob.stream().allMatch(event -> event.jobId().equals(jobA)));
    }

    @Test
    void validatesWorkspaceAndLimit() {
        assertThrows(IllegalArgumentException.class, () -> audit.recent(" ", null, 10));
        assertThrows(IllegalArgumentException.class, () -> audit.recent("workspace-a", null, 0));
        assertThrows(IllegalArgumentException.class, () -> audit.recent("workspace-a", null, 1001));
    }

    private void insert(String workspace, UUID job, EventType type, Instant at) throws Exception {
        try (var c = source.getConnection(); var p = c.prepareStatement(
                "INSERT INTO farabi_storage_inventory_audit "
                        + "(id,workspace_id,job_id,actor_id,event_type,attempt,occurred_at) VALUES(?,?,?,?,?,?,?)")) {
            p.setObject(1, UUID.randomUUID());
            p.setString(2, workspace);
            p.setObject(3, job);
            p.setString(4, "operator");
            p.setString(5, type.name());
            p.setInt(6, 1);
            p.setTimestamp(7, Timestamp.from(at));
            p.executeUpdate();
        }
    }
}
