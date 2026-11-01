package tech.kayys.farabi.jdbc;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.*;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.DatasetStorageAccountingPorts.Usage;

class JdbcWorkspaceStorageStoreTest {
    private DataSource source;
    private JdbcWorkspaceStorageStore store;
    private final Instant now=Instant.parse("2026-11-01T00:00:00Z");

    @BeforeEach void setUp() throws Exception {
        source=new JdbcDatasetImportJobStoreTest.TestDataSource("jdbc:h2:mem:quota_"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        store=new JdbcWorkspaceStorageStore(source,100);
        try(Connection c=source.getConnection();Statement s=c.createStatement()){
            s.execute("CREATE TABLE farabi_workspace_storage(workspace_id VARCHAR(200) PRIMARY KEY,quota_bytes BIGINT NOT NULL,used_bytes BIGINT NOT NULL,reserved_bytes BIGINT NOT NULL,updated_at TIMESTAMP WITH TIME ZONE NOT NULL)");
            s.execute("CREATE TABLE farabi_storage_reservations(id UUID PRIMARY KEY,workspace_id VARCHAR(200) NOT NULL,reserved_bytes BIGINT NOT NULL,dataset_id UUID UNIQUE,status VARCHAR(20) NOT NULL,created_at TIMESTAMP WITH TIME ZONE NOT NULL,updated_at TIMESTAMP WITH TIME ZONE NOT NULL)");
        }
    }

    @Test void reservationsPreventConcurrentOvercommitAndCanBeReleased() {
        UUID first=UUID.randomUUID();
        store.reserve("workspace",first,70,now);
        Usage usage=store.usage("workspace").orElseThrow();
        assertEquals(100,usage.quotaBytes());
        assertEquals(70,usage.reservedBytes());
        assertEquals(30,usage.availableBytes());
        assertThrows(JdbcWorkspaceStorageStore.StorageQuotaExceededException.class,
                ()->store.reserve("workspace",UUID.randomUUID(),31,now.plusSeconds(1)));
        store.releaseReservation(first,"workspace",now.plusSeconds(2));
        assertEquals(0,store.usage("workspace").orElseThrow().reservedBytes());
        store.releaseReservation(first,"workspace",now.plusSeconds(3));
        assertEquals(0,store.usage("workspace").orElseThrow().reservedBytes());
    }
}
