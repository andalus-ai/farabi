package tech.kayys.farabi.jdbc;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.DatasetStorageInventoryService.Finding;
import tech.kayys.farabi.engine.DatasetStorageInventoryService.FindingType;
import tech.kayys.farabi.engine.DatasetStorageInventoryService.Report;

class JdbcDatasetStorageInventoryReportStoreTest {
    @Test void savesAndReadsWorkspaceScopedReportsWithFindings() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:inventory_reports;DB_CLOSE_DELAY=-1");
        try (Connection c=source.getConnection(); Statement s=c.createStatement()) {
            s.execute("CREATE TABLE farabi_workspace_storage(workspace_id VARCHAR(200) PRIMARY KEY)");
            s.execute("INSERT INTO farabi_workspace_storage VALUES('workspace-a')");
            s.execute("""
                CREATE TABLE farabi_storage_inventory_reports(
                  id UUID PRIMARY KEY, workspace_id VARCHAR(200) NOT NULL, inspected_at TIMESTAMP WITH TIME ZONE NOT NULL,
                  orphan_grace_seconds BIGINT NOT NULL, catalog_objects INT NOT NULL, provider_objects INT NOT NULL,
                  finding_count INT NOT NULL, FOREIGN KEY(workspace_id) REFERENCES farabi_workspace_storage(workspace_id))
                """);
            s.execute("""
                CREATE TABLE farabi_storage_inventory_findings(
                  report_id UUID NOT NULL, workspace_id VARCHAR(200) NOT NULL, finding_index INT NOT NULL,
                  finding_type VARCHAR(40) NOT NULL, location_ref VARCHAR(2048) NOT NULL,
                  expected_bytes BIGINT NOT NULL, actual_bytes BIGINT NOT NULL, object_last_modified TIMESTAMP WITH TIME ZONE,
                  PRIMARY KEY(report_id,finding_index), FOREIGN KEY(report_id) REFERENCES farabi_storage_inventory_reports(id))
                """);
        }
        var store = new JdbcDatasetStorageInventoryReportStore(source);
        Instant now = Instant.parse("2026-11-01T00:00:00Z");
        var expected = new Report(UUID.randomUUID(), "workspace-a", now, Duration.ofHours(24), 2, 2,
                List.of(new Finding(FindingType.MISSING_OBJECT, "s3://bucket/object", 10, 0, null)));
        store.save(expected);
        var actual = store.recent("workspace-a", 10);
        assertEquals(1, actual.size());
        assertEquals(expected.id(), actual.get(0).id());
        assertEquals(expected.findings(), actual.get(0).findings());
        assertTrue(store.recent("workspace-b", 10).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> store.recent("workspace-a", 101));
    }
}
