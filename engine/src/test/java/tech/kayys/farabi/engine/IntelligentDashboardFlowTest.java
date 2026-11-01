package tech.kayys.farabi.engine;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.DashboardManifests.Manifest;
import tech.kayys.farabi.engine.DashboardPlanner.*;
import tech.kayys.farabi.engine.DynamicDashboardService.DynamicDraft;
import tech.kayys.farabi.engine.InsightEngine.Type;
import tech.kayys.farabi.engine.VisualizationContracts.*;
import static org.junit.jupiter.api.Assertions.*;

/** The north-star journey, end to end, with the negative cases the guardrails require. */
class IntelligentDashboardFlowTest {
    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final Set<PolicyAuthorizer.Grant> grants = IntelligenceFixtures.grants("ws", "alice", "orders");
    private final Map<UUID, Manifest> manifestStore = new HashMap<>();
    private final Map<UUID, DynamicDraft> draftStore = new HashMap<>();
    private final List<Audit.Event> events = new ArrayList<>();
    private final IntelligenceFixtures.FakeSource source = new IntelligenceFixtures.FakeSource();
    private final MutableClock clock = new MutableClock();
    private DynamicDashboardService service;
    private LiveDashboardRuntime runtime;

    private final DashboardManifests.Repository manifestRepo = new DashboardManifests.Repository() {
        public void save(Manifest m) { manifestStore.put(m.id(), m); }
        public Optional<Manifest> find(UUID id, String ws) { return Optional.ofNullable(manifestStore.get(id)).filter(m -> m.workspaceId().equals(ws)); }
    };
    private final DynamicDashboardService.DraftRepository draftRepo = new DynamicDashboardService.DraftRepository() {
        public void save(DynamicDraft d) { draftStore.put(d.id(), d); }
        public Optional<DynamicDraft> find(UUID id, String ws) { return Optional.ofNullable(draftStore.get(id)).filter(d -> d.workspaceId().equals(ws)); }
    };

    private DynamicDashboardService build(Proposer proposer) {
        var model = IntelligenceFixtures.model();
        var policy = IntelligenceFixtures.policy(grants);
        var validator = new DashboardSpecValidator(model, policy);
        runtime = new LiveDashboardRuntime(manifestRepo, validator, new StructuredQueryCompiler(model), source, new InsightEngine(), events::add);
        return new DynamicDashboardService(model, policy, proposer, validator, draftRepo, runtime, manifestRepo,
            new ManifestPromotionService(manifestRepo, clock), clock, Duration.ofHours(24), events::add);
    }

    @BeforeEach void setUp() { service = build(new HeuristicProposer()); }

    private Set<String> actions() { Set<String> s = new TreeSet<>(); events.forEach(e -> s.add(e.action())); return s; }

    @Test void promptToPreviewToPromotionToStaticDashboard() {
        DynamicDraft draft = service.propose("ws", "alice", "Show revenue trend by region and revenue share by channel");
        assertFalse(draft.spec().charts().isEmpty());

        var preview = service.preview(draft.id(), "ws", "alice");
        assertEquals(LiveDashboardRuntime.Mode.DYNAMIC_PREVIEW, preview.mode());
        assertEquals(draft.spec().charts().size(), preview.rendered().data().size());
        assertEquals("ready", preview.transport().status());
        assertTrue(preview.insights().stream().anyMatch(i -> i.type() == Type.LATEST_CHANGE));
        assertTrue(manifestStore.isEmpty(), "previewing must not publish anything");

        Manifest manifest = service.promote(draft.id(), "ws", "alice", preview.specDigest());
        assertEquals(1, manifest.revision());
        assertEquals(draft.id(), manifest.draftId());
        assertEquals(draft.spec(), manifest.spec(), "promotion must not alter the reviewed spec");

        var live = runtime.loadPublished(manifest.id(), "ws", "alice");
        assertEquals(LiveDashboardRuntime.Mode.STATIC_PUBLISHED, live.mode());
        assertEquals(preview.specDigest(), live.specDigest());
        assertEquals(preview.rendered().data(), live.rendered().data());
        assertTrue(actions().containsAll(Set.of("draft.created", "draft.previewed", "draft.promoted", "dashboard.loaded")), actions().toString());
    }

    @Test void everyExecutedQueryIsCompilerOutputOverTheCatalog() {
        var draft = service.propose("ws", "alice", "revenue trend by region");
        service.preview(draft.id(), "ws", "alice");
        assertFalse(source.executed.isEmpty());
        for (String sql : source.executed) {
            assertTrue(sql.startsWith("SELECT ") && sql.contains(" FROM \"sales\".\"orders\""), sql);
            assertFalse(sql.contains(";"), sql);
        }
    }

    @Test void promotionRequiresTheDigestThatWasReviewed() {
        var draft = service.propose("ws", "alice", "revenue by region");
        assertThrows(IllegalStateException.class, () -> service.promote(draft.id(), "ws", "alice", "deadbeef"));
        assertThrows(IllegalStateException.class, () -> service.promote(draft.id(), "ws", "alice", null));
        assertTrue(manifestStore.isEmpty());
        assertNotNull(service.promote(draft.id(), "ws", "alice", draft.digest()));
    }

    @Test void expiredDraftsCannotBePromoted() {
        var draft = service.propose("ws", "alice", "revenue by region");
        clock.now = clock.now.plus(Duration.ofHours(25));
        assertThrows(IllegalStateException.class, () -> service.promote(draft.id(), "ws", "alice", draft.digest()));
    }

    @Test void drafts_and_manifests_are_isolated_by_workspace_and_owner() {
        grants.addAll(IntelligenceFixtures.grants("ws", "bob", "orders"));
        grants.addAll(IntelligenceFixtures.grants("other", "alice", "orders"));
        var draft = service.propose("ws", "alice", "revenue by region");
        assertThrows(IllegalArgumentException.class, () -> service.preview(draft.id(), "other", "alice"));
        assertThrows(IllegalArgumentException.class, () -> service.promote(draft.id(), "other", "alice", draft.digest()));
        assertThrows(SecurityException.class, () -> service.preview(draft.id(), "ws", "bob"));
        assertThrows(SecurityException.class, () -> service.promote(draft.id(), "ws", "bob", draft.digest()));
        var manifest = service.promote(draft.id(), "ws", "alice", draft.digest());
        assertThrows(IllegalArgumentException.class, () -> runtime.loadPublished(manifest.id(), "other", "alice"));
        assertThrows(IllegalArgumentException.class, () -> service.editPublished(manifest.id(), "other", "alice"));
    }

    @Test void revokedGrantsStopStaticDashboardsBeforeAnyDataIsFetched() {
        var draft = service.propose("ws", "alice", "revenue trend by region");
        var manifest = service.promote(draft.id(), "ws", "alice", draft.digest());
        grants.clear();
        int before = source.executed.size();
        assertThrows(DashboardSpecValidator.InvalidSpecException.class, () -> runtime.loadPublished(manifest.id(), "ws", "alice"));
        assertEquals(before, source.executed.size(), "no query may run once access is revoked");
    }

    @Test void editingProducesANewRevisionAndLeavesThePublishedOneUntouched() {
        var draft = service.propose("ws", "alice", "revenue by region");
        var v1 = service.promote(draft.id(), "ws", "alice", draft.digest());

        var editing = service.editPublished(v1.id(), "ws", "alice");
        assertEquals(v1.id(), editing.parentManifestId());
        List<ChartSpec> charts = new ArrayList<>(editing.spec().charts());
        charts.add(new ChartSpec("extra", Kind.BAR, "Revenue by channel", new Binding("orders", "channel", "revenue", null), List.of()));
        var revised = service.revise(editing.id(), "ws", "alice", new DashboardSpec(editing.spec().title(), charts, DashboardLayouts.flow(charts)));
        assertNotEquals(editing.id(), revised.id());

        var v2 = service.promote(revised.id(), "ws", "alice", revised.digest());
        assertEquals(2, v2.revision());
        assertEquals(2, manifestStore.size());
        assertEquals(draft.spec(), manifestRepo.find(v1.id(), "ws").orElseThrow().spec(), "published revision 1 must be immutable");
        assertEquals(charts.size(), v2.spec().charts().size());
    }

    @Test void untrustedProposalsAreValidatedNotTrusted() {
        Proposer rogue = req -> {
            var c = new ChartSpec("x", Kind.KPI, "Salaries", new Binding("payroll", null, "salary", null), List.of());
            return new Proposal(new DashboardSpec("Leak", List.of(c), DashboardLayouts.flow(List.of(c))), List.of(), "rogue-llm");
        };
        var guarded = build(rogue);
        var ex = assertThrows(DashboardSpecValidator.InvalidSpecException.class, () -> guarded.propose("ws", "alice", "anything"));
        assertEquals("DATASET_UNAVAILABLE", ex.issues().get(0).code());
        assertTrue(draftStore.isEmpty());
        assertTrue(actions().contains("draft.rejected"));
        assertTrue(source.executed.isEmpty());
    }

    @Test void proposersOnlySeeTheCallersAuthorizedCatalog() {
        List<String> seen = new ArrayList<>();
        var spy = build(req -> { req.catalog().datasets().forEach(d -> seen.add(d.id())); return new HeuristicProposer().propose(req); });
        spy.propose("ws", "alice", "revenue by region");
        assertEquals(List.of("orders"), seen);
        grants.clear();
        assertThrows(SecurityException.class, () -> spy.propose("ws", "alice", "revenue by region"));
    }

    @Test void agentFilterInjectionIsRejectedBeforeDraftCreationOrDataAccess() {
        Proposer rogue = req -> {
            var chart = new ChartSpec("injected", Kind.KPI, "Revenue", new Binding("orders", null, "revenue", null),
                List.of(new Filter("region", "eq", "x' OR 1=1")));
            return new Proposal(new DashboardSpec("Injected filter", List.of(chart), DashboardLayouts.flow(List.of(chart))), List.of(), "rogue-llm");
        };
        var guarded = build(rogue);

        var ex = assertThrows(DashboardSpecValidator.InvalidSpecException.class,
            () -> guarded.propose("ws", "alice", "show revenue"));
        assertTrue(ex.issues().stream().anyMatch(issue -> issue.code().equals("INVALID_QUERY")), ex.issues().toString());
        assertTrue(draftStore.isEmpty(), "an invalid proposal must not become a draft");
        assertTrue(source.executed.isEmpty(), "proposal validation must not execute data queries");
        assertTrue(actions().contains("draft.rejected"));
    }

    @Test void oversizedAgentProposalIsRejectedBeforeDraftCreation() {
        Proposer rogue = req -> {
            List<ChartSpec> charts = new ArrayList<>();
            for (int i = 0; i < DashboardSpecValidator.MAX_CHARTS + 1; i++) {
                charts.add(new ChartSpec("chart_" + i, Kind.KPI, "Revenue " + i,
                    new Binding("orders", null, "revenue", null), List.of()));
            }
            return new Proposal(new DashboardSpec("Too many charts", charts, DashboardLayouts.flow(charts)), List.of(), "rogue-llm");
        };
        var guarded = build(rogue);

        var ex = assertThrows(DashboardSpecValidator.InvalidSpecException.class,
            () -> guarded.propose("ws", "alice", "show revenue"));
        assertTrue(ex.issues().stream().anyMatch(issue -> issue.code().equals("TOO_MANY_CHARTS")), ex.issues().toString());
        assertTrue(draftStore.isEmpty(), "an oversized proposal must not become a draft");
        assertTrue(source.executed.isEmpty());
    }

    @Test void promptsAreBounded() {
        assertThrows(IllegalArgumentException.class, () -> service.propose("ws", "alice", "x".repeat(DynamicDashboardService.MAX_PROMPT + 1)));
    }
}
