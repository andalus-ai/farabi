package tech.kayys.farabi.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.DynamicDashboardService.DynamicDraft;
import tech.kayys.farabi.engine.VisualizationContracts.*;
import static org.junit.jupiter.api.Assertions.*;

/** Runs the real V5 migration against H2 and exercises the repository contract the service relies on. */
class JdbcDynamicDraftRepositoryTest {
    private JdbcDynamicDraftRepository repo;
    private final Instant now = Instant.parse("2026-11-01T00:00:00Z");

    @BeforeEach void setUp() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:drafts_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (var in = getClass().getResourceAsStream("/db/migration/V5__farabi_dynamic_drafts.sql");
             Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            String sql = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).replaceAll("(?m)^--.*$", "");
            for (String statement : sql.split(";")) if (!statement.isBlank()) st.execute(statement);
        } catch (IOException e) { throw new IllegalStateException(e); }
        repo = new JdbcDynamicDraftRepository(ds, new ObjectMapper(), Clock.fixed(now, ZoneOffset.UTC));
    }

    private DynamicDraft draft(String workspace) {
        var bar = new ChartSpec("bar", Kind.BAR, "Revenue by region", new Binding("orders", "region", "revenue", null), List.of(new Filter("channel", "eq", "web")));
        var kpi = new ChartSpec("kpi", Kind.KPI, "Total", new Binding("orders", null, "revenue", null), List.of());
        var spec = new DashboardSpec("Revenue", List.of(kpi, bar), List.of(new Placement("kpi", 0, 0, 4, 2), new Placement("bar", 0, 2, 6, 4)));
        UUID id = UUID.randomUUID();
        return new DynamicDraft(id, id, workspace, "alice", "revenue by region, web only", spec, List.of("Filtering Channel = web"),
            "heuristic-v1", UUID.randomUUID(), now, now.plusSeconds(86400));
    }

    @Test void roundTripsEveryFieldIncludingTheSpec() {
        var d = draft("ws");
        repo.save(d);
        assertEquals(d, repo.find(d.id(), "ws").orElseThrow());
    }

    @Test void readsAreScopedByWorkspace() {
        var d = draft("ws");
        repo.save(d);
        assertTrue(repo.find(d.id(), "other").isEmpty());
        assertTrue(repo.find(UUID.randomUUID(), "ws").isEmpty());
        assertFalse(repo.markPromoted(d.id(), "other"), "another workspace cannot claim the draft");
        assertTrue(repo.markPromoted(d.id(), "ws"));
    }

    @Test void promotionIsASingleWinnerCompareAndSetThatCanBeReleased() {
        var d = draft("ws");
        repo.save(d);
        assertTrue(repo.markPromoted(d.id(), "ws"));
        assertFalse(repo.markPromoted(d.id(), "ws"), "replay must lose");
        repo.reopen(d.id(), "ws");
        assertTrue(repo.markPromoted(d.id(), "ws"), "a released draft can be promoted again");
    }

    @Test void duplicateIdsAreRejected() {
        var d = draft("ws");
        repo.save(d);
        assertThrows(IllegalStateException.class, () -> repo.save(d));
    }
}
