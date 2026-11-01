package tech.kayys.farabi.engine;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import tech.kayys.farabi.engine.DashboardManifests.Manifest;
import tech.kayys.farabi.engine.DashboardPlanner.*;
import tech.kayys.farabi.engine.SemanticModel.DatasetPolicy;
import tech.kayys.farabi.engine.VisualizationContracts.DashboardSpec;

/**
 * Dynamic -> static lifecycle:
 *   propose (agent output, validated) -> preview (live, governed data) -> promote (explicit, digest-checked)
 *   -> immutable manifest -> editPublished/revise (new draft) -> promote (next revision).
 * Drafts are immutable; every change creates a new draft, and a published manifest is never modified.
 */
public final class DynamicDashboardService {
    public static final int MAX_PROMPT = 2000;

    /**
     * {@code lineageId} is shared by every revision of one dashboard (a fresh draft is its own lineage) and becomes the
     * manifest's {@code draftId}, so the existing UNIQUE(workspace, draft_id, revision) constraint makes concurrent
     * edits of the same revision fail safely instead of silently forking.
     */
    public record DynamicDraft(UUID id, UUID lineageId, String workspaceId, String ownerId, String prompt, DashboardSpec spec,
                               List<String> assumptions, String proposer, UUID parentManifestId, Instant createdAt, Instant expiresAt) {
        public DynamicDraft {
            Objects.requireNonNull(id); Objects.requireNonNull(lineageId); QueryContracts.required(workspaceId, "workspaceId"); QueryContracts.required(ownerId, "ownerId");
            QueryContracts.required(prompt, "prompt"); Objects.requireNonNull(spec); QueryContracts.required(proposer, "proposer");
            assumptions = List.copyOf(assumptions); Objects.requireNonNull(createdAt); Objects.requireNonNull(expiresAt);
        }
        public String digest() { return SpecDigest.of(spec); }
    }

    public interface DraftRepository {
        void save(DynamicDraft draft);
        Optional<DynamicDraft> find(UUID id, String workspaceId);
        /** Atomic compare-and-set: true only for the first caller. A draft can be promoted once. */
        boolean markPromoted(UUID id, String workspaceId);
        /** Releases the claim after a failed publish so the reviewer may retry. */
        void reopen(UUID id, String workspaceId);
    }

    private final SemanticModel model;
    private final DatasetPolicy policy;
    private final Proposer proposer;
    private final DashboardSpecValidator validator;
    private final DraftRepository drafts;
    private final LiveDashboardRuntime runtime;
    private final DashboardManifests.Repository manifests;
    private final ManifestPromotionService promotion;
    private final VisualizationComposer composer = new VisualizationComposer();
    private final Clock clock;
    private final Duration ttl;
    private final Audit.Sink audit;

    public DynamicDashboardService(SemanticModel model, DatasetPolicy policy, Proposer proposer, DashboardSpecValidator validator,
                                   DraftRepository drafts, LiveDashboardRuntime runtime, DashboardManifests.Repository manifests,
                                   ManifestPromotionService promotion, Clock clock, Duration ttl, Audit.Sink audit) {
        this.model = Objects.requireNonNull(model); this.policy = Objects.requireNonNull(policy);
        this.proposer = Objects.requireNonNull(proposer); this.validator = Objects.requireNonNull(validator);
        this.drafts = Objects.requireNonNull(drafts); this.runtime = Objects.requireNonNull(runtime);
        this.manifests = Objects.requireNonNull(manifests); this.promotion = Objects.requireNonNull(promotion);
        this.clock = Objects.requireNonNull(clock); this.ttl = Objects.requireNonNull(ttl); this.audit = Objects.requireNonNull(audit);
    }

    /** Natural-language request -> validated draft. The proposer only ever sees the caller's authorized catalog. */
    public DynamicDraft propose(String workspaceId, String subjectId, String prompt) {
        QueryContracts.required(workspaceId, "workspaceId"); QueryContracts.required(subjectId, "subjectId"); QueryContracts.required(prompt, "prompt");
        if (prompt.length() > MAX_PROMPT) throw new IllegalArgumentException("prompt too long");
        var catalog = model.viewFor(workspaceId, subjectId, policy);
        if (catalog.datasets().isEmpty()) throw new SecurityException("no datasets available");
        Proposal proposal = proposer.propose(new Request(workspaceId, subjectId, prompt, catalog));
        return store(workspaceId, subjectId, prompt, proposal.spec(), proposal.assumptions(), proposal.proposer(), null, null);
    }

    /** Conventional-editor entry point: a manifest reopened for editing becomes a draft for the next revision. */
    public DynamicDraft editPublished(UUID manifestId, String workspaceId, String subjectId) {
        Manifest m = manifests.find(Objects.requireNonNull(manifestId), workspaceId)
            .orElseThrow(() -> new IllegalArgumentException("manifest not found"));
        return store(workspaceId, subjectId, "edit of manifest " + m.id(), m.spec(), List.of(), "manual-edit", m.id(), m.draftId());
    }

    /** Conventional-editor save: same validation as agent output, always a new draft. */
    public DynamicDraft revise(UUID draftId, String workspaceId, String subjectId, DashboardSpec newSpec) {
        DynamicDraft d = owned(draftId, workspaceId, subjectId);
        return store(workspaceId, subjectId, d.prompt(), newSpec, d.assumptions(), "manual-edit", d.parentManifestId(), d.lineageId());
    }

    public LiveDashboardRuntime.LiveDashboard preview(UUID draftId, String workspaceId, String subjectId) {
        DynamicDraft d = owned(draftId, workspaceId, subjectId);
        return runtime.preview(d.id(), workspaceId, subjectId, d.spec());
    }

    /** Explicit promotion. The reviewer passes the digest they saw; a changed or stale draft is refused. */
    public Manifest promote(UUID draftId, String workspaceId, String subjectId, String reviewedDigest) {
        DynamicDraft d = owned(draftId, workspaceId, subjectId);
        if (!clock.instant().isBefore(d.expiresAt())) throw new IllegalStateException("draft expired");
        if (reviewedDigest == null || !MessageDigest.isEqual(reviewedDigest.getBytes(StandardCharsets.UTF_8), d.digest().getBytes(StandardCharsets.UTF_8)))
            throw new IllegalStateException("draft changed since review");
        validator.validate(workspaceId, subjectId, d.spec()).requireValid();   // grants may have changed since proposal

        int revision = 1;
        if (d.parentManifestId() != null)
            revision = manifests.find(d.parentManifestId(), workspaceId)
                .orElseThrow(() -> new IllegalStateException("parent manifest missing")).revision() + 1;
        if (!drafts.markPromoted(d.id(), workspaceId)) throw new IllegalStateException("draft already promoted");
        Manifest manifest;
        try {
            var composition = composer.compose(d.lineageId(), workspaceId, d.ownerId(), d.spec(), "draft:" + d.id() + ";proposer:" + d.proposer());
            manifest = promotion.publish(composition, workspaceId, subjectId, revision);
        } catch (RuntimeException e) {   // includes a revision conflict from a concurrent edit of the same parent
            drafts.reopen(d.id(), workspaceId);
            audit.record(new Audit.Event("draft.promotion_failed", workspaceId, subjectId, "draft=" + d.id() + " reason=" + e.getClass().getSimpleName()));
            throw e;
        }
        audit.record(new Audit.Event("draft.promoted", workspaceId, subjectId,
            "draft=" + d.id() + " manifest=" + manifest.id() + " revision=" + revision + " digest=" + d.digest()));
        return manifest;
    }

    private DynamicDraft store(String ws, String subject, String prompt, DashboardSpec spec, List<String> assumptions, String by, UUID parent, UUID lineage) {
        var result = validator.validate(ws, subject, spec);
        if (!result.valid()) {
            audit.record(new Audit.Event("draft.rejected", ws, subject, "issues=" + result.issues().stream().map(i -> i.code()).toList()));
            result.requireValid();
        }
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        DynamicDraft draft = new DynamicDraft(id, lineage == null ? id : lineage, ws, subject, prompt, spec, assumptions, by, parent, now, now.plus(ttl));
        drafts.save(draft);
        audit.record(new Audit.Event("draft.created", ws, subject, "draft=" + draft.id() + " proposer=" + by + " digest=" + draft.digest()));
        return draft;
    }

    private DynamicDraft owned(UUID draftId, String workspaceId, String subjectId) {
        QueryContracts.required(workspaceId, "workspaceId"); QueryContracts.required(subjectId, "subjectId");
        DynamicDraft d = drafts.find(Objects.requireNonNull(draftId), workspaceId)
            .filter(x -> x.workspaceId().equals(workspaceId))
            .orElseThrow(() -> new IllegalArgumentException("draft not found"));
        if (!d.ownerId().equals(subjectId)) throw new SecurityException("draft belongs to another subject");
        return d;
    }
}
