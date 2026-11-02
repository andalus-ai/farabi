package tech.kayys.farabi.jdbc;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.*;
import java.time.*;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JdbcDatasetDeletionApprovalStoreTest {
    private DataSource source;
    private JdbcDatasetDeletionApprovalStore store;
    private final Instant now=Instant.parse("2026-11-02T00:00:00Z");
    private final UUID dataset=UUID.randomUUID();

    @BeforeEach void setUp() throws Exception {
        source=new JdbcDatasetImportJobStoreTest.TestDataSource("jdbc:h2:mem:delete_approval_"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        store=new JdbcDatasetDeletionApprovalStore(source);
        try(Connection c=source.getConnection();Statement s=c.createStatement()){
            s.execute("CREATE TABLE farabi_datasets(id UUID PRIMARY KEY,workspace_id VARCHAR(200),location_ref VARCHAR(2048),source_id VARCHAR(200),created_at TIMESTAMP WITH TIME ZONE,lifecycle VARCHAR(40),deletion_requested_at TIMESTAMP WITH TIME ZONE,updated_at TIMESTAMP WITH TIME ZONE)");
            s.execute("CREATE TABLE farabi_dataset_deletion_approvals(id UUID PRIMARY KEY,workspace_id VARCHAR(200),dataset_id UUID,requester_id VARCHAR(300),request_reason TEXT,requested_at TIMESTAMP WITH TIME ZONE,expires_at TIMESTAMP WITH TIME ZONE,status VARCHAR(20),approver_id VARCHAR(300),decision_reason TEXT,decided_at TIMESTAMP WITH TIME ZONE,deletion_job_id UUID)");
            s.execute("CREATE TABLE farabi_dataset_deletions(id UUID PRIMARY KEY,workspace_id VARCHAR(200),dataset_id UUID,location_ref VARCHAR(2048),status VARCHAR(20),attempts INTEGER,max_attempts INTEGER,available_at TIMESTAMP WITH TIME ZONE,created_at TIMESTAMP WITH TIME ZONE,updated_at TIMESTAMP WITH TIME ZONE,approval_id UUID)");
            s.execute("CREATE TABLE farabi_dataset_legal_holds(id UUID PRIMARY KEY,workspace_id VARCHAR(200),dataset_id UUID,released_at TIMESTAMP WITH TIME ZONE)");
            s.execute("CREATE TABLE farabi_dataset_retention_exemptions(id UUID PRIMARY KEY,workspace_id VARCHAR(200),dataset_id UUID,revoked_at TIMESTAMP WITH TIME ZONE,expires_at TIMESTAMP WITH TIME ZONE)");
            s.execute("CREATE TABLE farabi_dataset_retention_policies(id UUID PRIMARY KEY,workspace_id VARCHAR(200),source_id VARCHAR(200),retention_days INTEGER,effective_at TIMESTAMP WITH TIME ZONE,created_at TIMESTAMP WITH TIME ZONE)");
            s.execute("CREATE TABLE farabi_dataset_deletion_approval_audit(id UUID PRIMARY KEY,approval_id UUID,workspace_id VARCHAR(200),dataset_id UUID,event_type VARCHAR(20),actor_id VARCHAR(300),reason TEXT,occurred_at TIMESTAMP WITH TIME ZONE)");
            try(PreparedStatement p=c.prepareStatement("INSERT INTO farabi_datasets(id,workspace_id,location_ref,source_id,created_at,lifecycle) VALUES(?,?,'object://dataset','upload',?,'PROFILED')")){
                p.setObject(1,dataset);p.setString(2,"ws");p.setTimestamp(3,Timestamp.from(now.minus(Duration.ofDays(100))));p.executeUpdate();
            }
            try(PreparedStatement p=c.prepareStatement("INSERT INTO farabi_dataset_retention_policies(id,workspace_id,source_id,retention_days,effective_at,created_at) VALUES(?,?,'upload',30,?,?)")){
                p.setObject(1,UUID.randomUUID());p.setString(2,"ws");p.setTimestamp(3,Timestamp.from(now.minus(Duration.ofDays(10))));p.setTimestamp(4,Timestamp.from(now.minus(Duration.ofDays(10))));p.executeUpdate();
            }
        }
    }

    @Test void requesterCannotApproveAndDifferentApproverCreatesJobAtomically() throws Exception {
        var pending=store.request(dataset,"ws","maker","obsolete data",now,Duration.ofHours(12));
        assertEquals("PENDING",pending.status());
        assertThrows(SecurityException.class,()->store.approve(pending.id(),"ws","maker","self approval",now.plusSeconds(1),4));
        var approved=store.approve(pending.id(),"ws","checker","approved after review",now.plusSeconds(2),4);
        assertEquals("APPROVED",approved.status());
        assertEquals("maker",approved.requesterId());
        assertEquals("checker",approved.approverId());
        assertNotNull(approved.deletionJobId());
        assertEquals(2,store.audit(pending.id(),"ws").size());
        assertTrue(store.audit(pending.id(),"other").isEmpty());
        try(Connection c=source.getConnection();Statement s=c.createStatement()){
            try(ResultSet r=s.executeQuery("SELECT status,approval_id FROM farabi_dataset_deletions")){
                assertTrue(r.next());assertEquals("QUEUED",r.getString(1));assertEquals(pending.id(),r.getObject(2,UUID.class));
            }
            try(ResultSet r=s.executeQuery("SELECT COUNT(*) FROM farabi_dataset_deletion_approval_audit")){
                assertTrue(r.next());assertEquals(2,r.getInt(1));
            }
            try(ResultSet r=s.executeQuery("SELECT lifecycle FROM farabi_datasets WHERE id='"+dataset+"'")){
                assertTrue(r.next());assertEquals("DELETE_PENDING",r.getString(1));
            }
        }
    }

    @Test void activeLegalHoldBlocksApproval() throws Exception {
        var pending=store.request(dataset,"ws","maker","obsolete data",now,Duration.ofHours(12));
        try(Connection c=source.getConnection();PreparedStatement p=c.prepareStatement("INSERT INTO farabi_dataset_legal_holds(id,workspace_id,dataset_id) VALUES(?,?,?)")){
            p.setObject(1,UUID.randomUUID());p.setString(2,"ws");p.setObject(3,dataset);p.executeUpdate();
        }
        assertThrows(IllegalStateException.class,()->store.approve(pending.id(),"ws","checker","reviewed",now.plusSeconds(1),4));
        assertEquals("PENDING",store.find(pending.id(),"ws").orElseThrow().status());
    }

    @Test void approvalCannotBeUsedAcrossWorkspaceOrAfterExpiry() {
        var pending=store.request(dataset,"ws","maker","obsolete data",now,Duration.ofSeconds(30));
        assertTrue(store.find(pending.id(),"other").isEmpty());
        var expired=store.approve(pending.id(),"ws","checker","late approval",now.plusSeconds(31),4);
        assertEquals("EXPIRED",expired.status());
        assertEquals("EXPIRED",store.find(pending.id(),"ws").orElseThrow().status());
        var replacement=store.request(dataset,"ws","maker-2","new request",now.plusSeconds(32),Duration.ofHours(1));
        assertEquals("PENDING",replacement.status());
    }
}
