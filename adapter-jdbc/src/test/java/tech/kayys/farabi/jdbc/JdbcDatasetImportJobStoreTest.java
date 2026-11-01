package tech.kayys.farabi.jdbc;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.*;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.DatasetImportJobPorts.*;

class JdbcDatasetImportJobStoreTest {
    private DataSource source;
    private JdbcDatasetImportJobStore store;
    private final Instant now=Instant.parse("2026-11-01T00:00:00Z");

    @BeforeEach void setUp() throws Exception {
        String url="jdbc:h2:mem:jobs_"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        source=new JdbcDatasetCatalogTest.DriverDataSource(url);
        store=new JdbcDatasetImportJobStore(source);
        try(Connection c=source.getConnection();Statement s=c.createStatement()){
            s.execute("CREATE TABLE farabi_datasets(id UUID PRIMARY KEY, workspace_id VARCHAR(200) NOT NULL, name VARCHAR(300) NOT NULL, source_id VARCHAR(200) NOT NULL, format VARCHAR(40) NOT NULL, location_ref VARCHAR(2000) NOT NULL, lifecycle VARCHAR(40) NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL)");
            s.execute("CREATE TABLE farabi_dataset_import_jobs(id UUID PRIMARY KEY, workspace_id VARCHAR(200) NOT NULL, dataset_id UUID NOT NULL, idempotency_key VARCHAR(200) NOT NULL, status VARCHAR(20) NOT NULL, attempts INTEGER NOT NULL, max_attempts INTEGER NOT NULL, lease_owner VARCHAR(200), lease_token UUID, lease_until TIMESTAMP WITH TIME ZONE, error_code VARCHAR(100), error_message VARCHAR(2000), created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL, UNIQUE(workspace_id,idempotency_key), FOREIGN KEY(dataset_id) REFERENCES farabi_datasets(id))");
            s.execute("INSERT INTO farabi_datasets VALUES('00000000-0000-0000-0000-000000000001','ws','data','source','CSV','file://x','REGISTERED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        }
    }

    @Test void enqueueIsIdempotentWithinWorkspaceAndClaimIsFenced() {
        UUID dataset=UUID.fromString("00000000-0000-0000-0000-000000000001");
        Job first=store.enqueue(new NewJob("ws",dataset,"request-1",2,now));
        Job repeated=store.enqueue(new NewJob("ws",dataset,"request-1",2,now.plusSeconds(1)));
        assertEquals(first.id(),repeated.id());
        assertEquals(first.id(),store.findByIdempotencyKey("ws","request-1").orElseThrow().id());

        Job claimed=store.claim(new Lease("worker-a",now,now.plusSeconds(30))).orElseThrow();
        assertEquals(Status.RUNNING,claimed.status());
        assertEquals(1,claimed.attempts());
        assertFalse(store.complete(claimed.id(),UUID.randomUUID(),now.plusSeconds(2)));
        assertTrue(store.fail(claimed.id(),claimed.leaseToken(),"TEMPORARY","retry",now.plusSeconds(2)));
        assertEquals(Status.QUEUED,store.find(claimed.id(),"ws").orElseThrow().status());

        Job second=store.claim(new Lease("worker-b",now.plusSeconds(3),now.plusSeconds(33))).orElseThrow();
        assertTrue(store.complete(second.id(),second.leaseToken(),now.plusSeconds(4)));
        assertEquals(Status.SUCCEEDED,store.find(second.id(),"ws").orElseThrow().status());
        assertFalse(store.complete(second.id(),second.leaseToken(),now.plusSeconds(5)));
    }
}
