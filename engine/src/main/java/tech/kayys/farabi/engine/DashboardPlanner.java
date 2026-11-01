package tech.kayys.farabi.engine;

import java.util.*;
import tech.kayys.farabi.engine.SemanticModel.CatalogView;
import tech.kayys.farabi.engine.VisualizationContracts.DashboardSpec;

/**
 * Port for "intelligence" that turns a natural-language request into a dashboard proposal.
 * Implementations (the deterministic {@link HeuristicProposer}, an Andalus/LLM adapter, ...) see only the
 * caller's authorized catalog, and their output is untrusted data: it is always validated before use.
 */
public final class DashboardPlanner {
    private DashboardPlanner() {}

    public record Request(String workspaceId, String subjectId, String prompt, CatalogView catalog) {
        public Request {
            QueryContracts.required(workspaceId, "workspaceId");
            QueryContracts.required(subjectId, "subjectId");
            QueryContracts.required(prompt, "prompt");
            Objects.requireNonNull(catalog);
        }
    }

    public record Proposal(DashboardSpec spec, List<String> assumptions, String proposer) {
        public Proposal {
            Objects.requireNonNull(spec);
            assumptions = List.copyOf(Objects.requireNonNull(assumptions));
            QueryContracts.required(proposer, "proposer");
        }
    }

    @FunctionalInterface public interface Proposer { Proposal propose(Request request); }
}
