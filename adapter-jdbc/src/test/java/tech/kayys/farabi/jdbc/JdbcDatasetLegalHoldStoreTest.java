package tech.kayys.farabi.jdbc;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.*;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JdbcDatasetLegalHoldStoreTest {
    private DataSource source;
    private JdbcDatasetLegalHoldStore store;
    private final Instant now=Instant.parse("2026-11-02T00:00:00Z");
    private final UUID dataset=UUID.randomUUID();

    @BeforeEach void setUp() throws Exception {
        source=new JdbcDatasetImportJobStoreTest.TestDataSource("jdbc:h2:mem:holds_"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        store=new JdbcDatasetLegalHoldStore(source);
        try(Connection c=source.getConnection();Statement s=c.createStatement()){
            s.execute("CREATE TABLE farabi_datasets(id UUID PRIMARY KEY,workspace_id VARCHAR(200) NOT NULL)");
            s.execute("CREATE TABLE farabi_dataset_deletions(id UUID PRIMARY KEY,dataset_id UUID,workspace_id VARCHAR(200),status VARCHAR(20))");
            s.execute("CREATE TABLE farabi_dataset_legal_holds(id UUID PRIMARY KEY,workspace_id VARCHAR(200),dataset_id UUID,reason TEXT,placed_by VARCHAR(300),placed_at TIMESTAMP WITH TIME ZONE,released_by VARCHAR(300),released_at TIMESTAMP WITH TIME ZONE,release_reason TEXT)");
            s.execute("CREATE TABLE farabi_dataset_legal_hold_audit(id UUID PRIMARY KEY,hold_id UUID,workspace_id VARCHAR(200),dataset_id UUID,event_type VARCHAR(20),actor_id VARCHAR(300),reason TEXT,occurred_at TIMESTAMP WITH TIME ZONE)");
            try(PreparedStatement p=c.prepareStatement("INSERT INTO farabi_datasets(id,workspace_id) VALUES(?,?)")){
                p.setObject(1,dataset);p.setString(2,"ws");p.executeUpdate();
            }
        }
    }

    @Test void placeAndReleaseAreAuditedAndWorkspaceScoped() throws Exception {
        var hold=store.place(dataset,"ws","alice","regulatory inquiry",now);
        assertTrue(hold.active());
        assertEquals(1,store.recent("ws",dataset,10).size());
        assertTrue(store.recent("other",dataset,10).isEmpty());
        var released=store.release(hold.id(),"ws","bob","review completed",now.plusSeconds(60));
        assertFalse(released.active());
        assertEquals("bob",released.releasedBy());
        try(Connection c=source.getConnection();Statement s=c.createStatement();
            ResultSet r=s.executeQuery("SELECT event_type FROM farabi_dataset_legal_hold_audit ORDER BY occurred_at")){
            assertTrue(r.next());assertEquals("PLACED",r.getString(1));
            assertTrue(r.next());assertEquals("RELEASED",r.getString(1));
            assertFalse(r.next());
        }
    }

    @Test void cannotPlaceSecondActiveHoldOrHoldDuringRunningDeletion() throws Exception {
        store.place(dataset,"ws","alice","first hold",now);
        assertThrows(IllegalStateException.class,()->store.place(dataset,"ws","alice","second hold",now.plusSeconds(1)));
        try(Connection c=source.getConnection();PreparedStatement p=c.prepareStatement("INSERT INTO farabi_dataset_deletions(id,dataset_id,workspace_id,status) VALUES(?,?,?,'RUNNING')")){
            p.setObject(1,UUID.randomUUID());p.setObject(2,dataset);p.setString(3,"ws");p.executeUpdate();
        }
        // Release first so the RUNNING guard is the condition under test.
        var active=store.recent("ws",dataset,10).get(0);
        store.release(active.id(),"ws","alice","release for test",now.plusSeconds(2));
        assertThrows(IllegalStateException.class,()->store.place(dataset,"ws","alice","too late",now.plusSeconds(3)));
    }
}
