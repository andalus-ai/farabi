package tech.kayys.farabi.engine;

import java.util.*;
import tech.kayys.farabi.engine.DashboardArtifacts.*;
import tech.kayys.farabi.engine.VisualizationContracts.*;

/**
 * Converts a validated visualization layout into an existing dashboard draft.
 * Chart specifications remain separate from the legacy minimal Chart record.
 */
public final class VisualizationComposer {
    public record Composition(Draft draft,DashboardSpec visualization) {
        public Composition {Objects.requireNonNull(draft);Objects.requireNonNull(visualization);}
    }
    public Composition compose(String workspace,String owner,DashboardSpec spec,String provenance) {
        return compose(UUID.randomUUID(),workspace,owner,spec,provenance);
    }
    /** Variant that keeps a caller-chosen draft id so manifests trace back to the reviewed draft. */
    public Composition compose(UUID draftId,String workspace,String owner,DashboardSpec spec,String provenance) {
        Objects.requireNonNull(draftId);
        QueryContracts.required(workspace,"workspace");
        QueryContracts.required(owner,"owner");
        Objects.requireNonNull(spec);
        List<Chart> charts=spec.charts().stream().map(c->new Chart(
            c.id(),c.kind().name().toLowerCase(Locale.ROOT),c.binding().datasetId(),c.binding().valueField())).toList();
        return new Composition(new Draft(draftId,workspace,owner,spec.title(),charts,provenance),spec);
    }
}
