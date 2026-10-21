package tech.kayys.farabi.engine;

import java.time.Clock;
import java.util.*;
import tech.kayys.farabi.engine.DashboardManifests.*;
import tech.kayys.farabi.engine.VisualizationComposer.Composition;

/** Promote the full validated visualization specification, not just legacy chart summaries. */
public final class ManifestPromotionService {
    private final Repository repository;
    private final Clock clock;
    public ManifestPromotionService(Repository repository,Clock clock) {
        this.repository=Objects.requireNonNull(repository);this.clock=Objects.requireNonNull(clock);
    }
    public Manifest publish(Composition composition,String workspace,String subject,int revision) {
        Objects.requireNonNull(composition);QueryContracts.required(workspace,"workspace");QueryContracts.required(subject,"subject");
        var draft=composition.draft();
        if(!workspace.equals(draft.workspaceId())||!subject.equals(draft.ownerId()))
            throw new SecurityException("manifest publication denied");
        if(!draft.title().equals(composition.visualization().title()))
            throw new IllegalArgumentException("draft and visualization titles differ");
        Manifest manifest=new Manifest(UUID.randomUUID(),draft.id(),workspace,revision,SCHEMA_VERSION,
            composition.visualization(),draft.promptProvenance(),subject,clock.instant());
        repository.save(manifest);
        return manifest;
    }
    public Manifest get(UUID id,String workspace) {
        QueryContracts.required(workspace,"workspace");
        return repository.find(Objects.requireNonNull(id),workspace)
            .orElseThrow(()->new IllegalArgumentException("manifest not found"));
    }
}
