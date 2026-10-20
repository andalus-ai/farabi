package tech.kayys.farabi.engine;

import java.time.Clock;
import java.util.*;
import tech.kayys.farabi.engine.DashboardArtifacts.*;

/** Publishes immutable dashboard definitions; the renderer is an adapter responsibility. */
public final class DashboardPromotionService {
    private final Repository repository;
    private final Clock clock;
    public DashboardPromotionService(Repository repository,Clock clock) {
        this.repository=Objects.requireNonNull(repository);this.clock=Objects.requireNonNull(clock);
    }
    public Published publish(Draft draft,String authenticatedWorkspace,String authenticatedSubject,int revision) {
        Objects.requireNonNull(draft);
        QueryContracts.required(authenticatedWorkspace,"workspace");
        QueryContracts.required(authenticatedSubject,"subject");
        if(!draft.workspaceId().equals(authenticatedWorkspace)||!draft.ownerId().equals(authenticatedSubject))
            throw new SecurityException("dashboard publish denied");
        Published published=new Published(UUID.randomUUID(),draft.id(),draft.workspaceId(),draft.title(),
            revision,draft.charts(),draft.promptProvenance(),authenticatedSubject,clock.instant());
        repository.save(published);
        return published;
    }
    public Published get(UUID id,String authenticatedWorkspace) {
        QueryContracts.required(authenticatedWorkspace,"workspace");
        Published published=repository.find(Objects.requireNonNull(id),authenticatedWorkspace)
            .orElseThrow(()->new IllegalArgumentException("dashboard not found"));
        if(!published.workspaceId().equals(authenticatedWorkspace))
            throw new IllegalArgumentException("dashboard not found");
        return published;
    }
}
