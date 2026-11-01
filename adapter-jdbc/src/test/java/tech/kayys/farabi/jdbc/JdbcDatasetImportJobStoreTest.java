package tech.kayys.farabi.jdbc;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.*;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.DatasetImportJobPorts.*;
import tech.kayys.farabi.engine.DatasetContracts;

class JdbcDatasetImportJobStoreTest {
    private DataSource source;
    private JdbcDatasetImportJobStore store;
    private final Instant now=Instant.parse("2026-11-01T00:00:00Z");

    @BeforeEach void setUp() throws Exception {
        String url="jdbc:h2:mem:jobs_"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        source=new TestDataSource(url);
        store=new JdbcDatasetImportJobStore(source);
        try(Connection c=source.getConnection();Statement s=c.createStatement()){
            s.execute("CREATE TABLE farabi_datasets(id UUID PRIMARY KEY, workspace_id VARCHAR(200) NOT NULL, name VARCHAR(300) NOT NULL, source_id VARCHAR(200) NOT NULL, format VARCHAR(40) NOT NULL, location_ref VARCHAR(2000) NOT NULL, lifecycle VARCHAR(40) NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL, storage_bytes BIGINT NOT NULL DEFAULT 0)");
            s.execute("CREATE TABLE farabi_dataset_import_jobs(id UUID PRIMARY KEY, workspace_id VARCHAR(200) NOT NULL, dataset_id UUID NOT NULL, idempotency_key VARCHAR(200) NOT NULL, status VARCHAR(20) NOT NULL, attempts INTEGER NOT NULL, max_attempts INTEGER NOT NULL, lease_owner VARCHAR(200), lease_token UUID, lease_until TIMESTAMP WITH TIME ZONE, available_at TIMESTAMP WITH TIME ZONE NOT NULL, error_code VARCHAR(100), error_message VARCHAR(2000), created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL, UNIQUE(workspace_id,idempotency_key), FOREIGN KEY(dataset_id) REFERENCES farabi_datasets(id))");
            s.execute("INSERT INTO farabi_datasets VALUES('00000000-0000-0000-0000-000000000001','ws','data','source','CSV','file://x','REGISTERED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)");
            s.execute("CREATE TABLE farabi_workspace_storage(workspace_id VARCHAR(200) PRIMARY KEY,quota_bytes BIGINT NOT NULL,used_bytes BIGINT NOT NULL,reserved_bytes BIGINT NOT NULL,updated_at TIMESTAMP WITH TIME ZONE NOT NULL)");
            s.execute("CREATE TABLE farabi_storage_reservations(id UUID PRIMARY KEY,workspace_id VARCHAR(200) NOT NULL,reserved_bytes BIGINT NOT NULL,dataset_id UUID UNIQUE,status VARCHAR(20) NOT NULL,created_at TIMESTAMP WITH TIME ZONE NOT NULL,updated_at TIMESTAMP WITH TIME ZONE NOT NULL)");
            s.execute("INSERT INTO farabi_workspace_storage VALUES('ws',1000,0,40,CURRENT_TIMESTAMP)");
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
        assertTrue(store.renew(claimed.id(),claimed.leaseToken(),now.plusSeconds(60),now.plusSeconds(1)));
        assertTrue(store.fail(claimed.id(),claimed.leaseToken(),"TEMPORARY","retry",now.plusSeconds(7),now.plusSeconds(2)));
        assertEquals(Status.QUEUED,store.find(claimed.id(),"ws").orElseThrow().status());
        assertTrue(store.claim(new Lease("worker-b",now.plusSeconds(6),now.plusSeconds(36))).isEmpty());
        Job second=store.claim(new Lease("worker-b",now.plusSeconds(8),now.plusSeconds(38))).orElseThrow();
        assertTrue(store.complete(second.id(),second.leaseToken(),now.plusSeconds(9)));
        assertEquals(Status.SUCCEEDED,store.find(second.id(),"ws").orElseThrow().status());
        assertFalse(store.complete(second.id(),second.leaseToken(),now.plusSeconds(5)));
    }


    @Test void datasetAndJobAreCommittedAtomicallyAndIdempotencyRaceRollsBackDataset() throws Exception {
        UUID datasetId=UUID.randomUUID();
        DatasetContracts.Dataset dataset=new DatasetContracts.Dataset(datasetId,"ws","new-data","source",
                DatasetContracts.Format.CSV,"local-file:ws:upload",DatasetContracts.Lifecycle.REGISTERED,now,now);
        Job created=store.registerAndEnqueue(dataset,new NewJob("ws",datasetId,"atomic-key",3,now));
        assertEquals(datasetId,created.datasetId());
        try(Connection c=source.getConnection();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM farabi_datasets")){
            assertTrue(r.next());assertEquals(2,r.getInt(1));
        }

        UUID duplicateDatasetId=UUID.randomUUID();
        DatasetContracts.Dataset duplicate=new DatasetContracts.Dataset(duplicateDatasetId,"ws","another-data","source",
                DatasetContracts.Format.CSV,"local-file:ws:other",DatasetContracts.Lifecycle.REGISTERED,now,now);
        Job replay=store.registerAndEnqueue(duplicate,new NewJob("ws",duplicateDatasetId,"atomic-key",3,now));
        assertEquals(created.id(),replay.id());
        try(Connection c=source.getConnection();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM farabi_datasets")){
            assertTrue(r.next());assertEquals(2,r.getInt(1),"failed job insert must roll back dataset insert");
        }
    }


    @Test void quotaReservationIsCommittedAtomicallyWithDatasetAndJob() throws Exception {
        UUID reservation=UUID.randomUUID(), datasetId=UUID.randomUUID();
        try(Connection c=source.getConnection();PreparedStatement p=c.prepareStatement(
                "INSERT INTO farabi_storage_reservations(id,workspace_id,reserved_bytes,status,created_at,updated_at) VALUES(?,? ,40,'RESERVED',?,?)")){
            p.setObject(1,reservation);p.setString(2,"ws");p.setTimestamp(3,Timestamp.from(now));p.setTimestamp(4,Timestamp.from(now));p.executeUpdate();
        }
        DatasetContracts.Dataset dataset=new DatasetContracts.Dataset(datasetId,"ws","quota-data","source",
                DatasetContracts.Format.CSV,"local-file:ws:quota",DatasetContracts.Lifecycle.REGISTERED,now,now);
        Job job=store.registerAndEnqueue(dataset,new NewJob("ws",datasetId,"quota-key",3,now),reservation,30);
        assertEquals(datasetId,job.datasetId());
        try(Connection c=source.getConnection();Statement s=c.createStatement();
            ResultSet r=s.executeQuery("SELECT quota_bytes,used_bytes,reserved_bytes FROM farabi_workspace_storage WHERE workspace_id='ws'")){
            assertTrue(r.next());assertEquals(1000,r.getLong(1));assertEquals(30,r.getLong(2));assertEquals(0,r.getLong(3));
        }
        try(Connection c=source.getConnection();PreparedStatement p=c.prepareStatement("SELECT storage_bytes FROM farabi_datasets WHERE id=?")){
            p.setObject(1,datasetId);try(ResultSet r=p.executeQuery()){assertTrue(r.next());assertEquals(30,r.getLong(1));}
        }
    }

    @Test void actualBytesCannotExceedReservationAndTransactionRollsBack() throws Exception {
        UUID reservation=UUID.randomUUID(), datasetId=UUID.randomUUID();
        try(Connection c=source.getConnection();PreparedStatement p=c.prepareStatement(
                "INSERT INTO farabi_storage_reservations(id,workspace_id,reserved_bytes,status,created_at,updated_at) VALUES(?,? ,10,'RESERVED',?,?)")){
            p.setObject(1,reservation);p.setString(2,"ws");p.setTimestamp(3,Timestamp.from(now));p.setTimestamp(4,Timestamp.from(now));p.executeUpdate();
        }
        DatasetContracts.Dataset dataset=new DatasetContracts.Dataset(datasetId,"ws","too-large","source",
                DatasetContracts.Format.CSV,"local-file:ws:too-large",DatasetContracts.Lifecycle.REGISTERED,now,now);
        assertThrows(IllegalArgumentException.class,()->store.registerAndEnqueue(dataset,
                new NewJob("ws",datasetId,"too-large-key",3,now),reservation,11));
        try(Connection c=source.getConnection();PreparedStatement p=c.prepareStatement("SELECT COUNT(*) FROM farabi_datasets WHERE id=?")){
            p.setObject(1,datasetId);try(ResultSet r=p.executeQuery()){assertTrue(r.next());assertEquals(0,r.getInt(1));}
        }
    }

    private record TestDataSource(String url) implements DataSource {
        public Connection getConnection() throws SQLException{return DriverManager.getConnection(url);}
        public Connection getConnection(String user,String password)throws SQLException{return DriverManager.getConnection(url,user,password);}
        public java.io.PrintWriter getLogWriter(){return null;}
        public void setLogWriter(java.io.PrintWriter writer){}
        public void setLoginTimeout(int seconds){}
        public int getLoginTimeout(){return 0;}
        public java.util.logging.Logger getParentLogger(){return java.util.logging.Logger.getGlobal();}
        public <T>T unwrap(Class<T> type)throws SQLException{throw new SQLException("not a wrapper");}
        public boolean isWrapperFor(Class<?> type){return false;}
    }
}
