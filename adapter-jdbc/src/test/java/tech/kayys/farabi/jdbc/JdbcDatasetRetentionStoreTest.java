package tech.kayys.farabi.jdbc;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.*;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JdbcDatasetRetentionStoreTest {
    private DataSource source;
    private JdbcDatasetRetentionStore store;
    private final Instant now=Instant.parse("2026-11-02T00:00:00Z");
    private final UUID dataset=UUID.randomUUID();

    @BeforeEach void setUp() throws Exception {
        source=new JdbcDatasetImportJobStoreTest.TestDataSource("jdbc:h2:mem:retention_"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        store=new JdbcDatasetRetentionStore(source);
        try(Connection c=source.getConnection();Statement s=c.createStatement()){
            s.execute("CREATE TABLE farabi_datasets(id UUID PRIMARY KEY,workspace_id VARCHAR(200),name VARCHAR(300),source_id VARCHAR(200),created_at TIMESTAMP WITH TIME ZONE,lifecycle VARCHAR(40))");
            s.execute("CREATE TABLE farabi_dataset_retention_policies(id UUID PRIMARY KEY,workspace_id VARCHAR(200),source_id VARCHAR(200),retention_days INTEGER,effective_at TIMESTAMP WITH TIME ZONE,created_by VARCHAR(300),created_at TIMESTAMP WITH TIME ZONE,supersedes_policy_id UUID)");
            s.execute("CREATE TABLE farabi_dataset_deletions(id UUID PRIMARY KEY,dataset_id UUID,workspace_id VARCHAR(200),status VARCHAR(20))");
            s.execute("CREATE TABLE farabi_dataset_retention_exemptions(id UUID PRIMARY KEY,workspace_id VARCHAR(200),dataset_id UUID,reason TEXT,created_by VARCHAR(300),created_at TIMESTAMP WITH TIME ZONE,expires_at TIMESTAMP WITH TIME ZONE,revoked_by VARCHAR(300),revoked_at TIMESTAMP WITH TIME ZONE,revoke_reason TEXT)");
            s.execute("CREATE TABLE farabi_dataset_retention_audit(id UUID PRIMARY KEY,workspace_id VARCHAR(200),policy_id UUID,dataset_id UUID,exemption_id UUID,event_type VARCHAR(30),actor_id VARCHAR(300),reason TEXT,occurred_at TIMESTAMP WITH TIME ZONE)");
            s.execute("CREATE TABLE farabi_dataset_deletions(id UUID PRIMARY KEY,dataset_id UUID,workspace_id VARCHAR(200),status VARCHAR(20))");
            try(PreparedStatement p=c.prepareStatement("INSERT INTO farabi_datasets(id,workspace_id,name,source_id,created_at,lifecycle) VALUES(?,?,?,?,?,'PROFILED')")){
                p.setObject(1,dataset);p.setString(2,"ws");p.setString(3,"sales");p.setString(4,"upload");p.setTimestamp(5,Timestamp.from(now.minusSeconds(86400*90L)));p.executeUpdate();
            }
        }
    }

    @Test void createsVersionedPolicyAndAuditsIt() throws Exception {
        var first=store.createPolicy("ws",null,30,now.minusSeconds(3600),"admin","baseline",now);
        var next=store.createPolicy("ws",null,60,now.plusSeconds(3600),"admin","extend retention",now.plusSeconds(1));
        assertEquals(first.id(),next.supersedesPolicyId());
        assertEquals(2,store.policies("ws",10).size());
        try(Connection c=source.getConnection();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM farabi_dataset_retention_audit WHERE event_type='POLICY_CREATED'")){
            assertTrue(r.next());assertEquals(2,r.getInt(1));
        }
    }

    @Test void createsAndRevokesWorkspaceScopedExemption() {
        var exemption=store.createExemption(dataset,"ws","admin","regulatory exception",now.plusSeconds(86400),now);
        assertNull(exemption.revokedAt());
        var revoked=store.revokeExemption(exemption.id(),"ws","admin","exception ended",now.plusSeconds(60));
        assertEquals("admin",revoked.revokedBy());
        assertEquals("exception ended",revoked.revokeReason());
        assertThrows(java.util.NoSuchElementException.class,()->store.revokeExemption(exemption.id(),"other","admin","wrong workspace",now.plusSeconds(120)));
    }
}
