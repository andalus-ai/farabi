package tech.kayys.farabi.engine;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import tech.kayys.farabi.engine.QueryContracts.*;
import tech.kayys.farabi.engine.QueryPorts.*;

/**
 * Persist-before-execute workflow. Ambiguous remote failures leave PENDING for manual reconciliation.
 * No automatic retry: remote execution might already have been accepted.
 */
public final class DurableQueryService {
    private final Authorizer authorizer;
    private final Compiler compiler;
    private final Executor executor;
    private final ExecutionRepository repository;
    private final Clock clock;

    public DurableQueryService(Authorizer authorizer, Compiler compiler, Executor executor,
                               ExecutionRepository repository, Clock clock) {
        this.authorizer=Objects.requireNonNull(authorizer); this.compiler=Objects.requireNonNull(compiler);
        this.executor=Objects.requireNonNull(executor); this.repository=Objects.requireNonNull(repository);
        this.clock=Objects.requireNonNull(clock);
    }

    public Execution submit(Request request) {
        Objects.requireNonNull(request);
        authorizer.authorize(request);
        Plan plan=Objects.requireNonNull(compiler.compile(request),"compiler returned null");
        if(!request.sourceId().equals(plan.sourceId())) throw new IllegalStateException("source mismatch");
        Instant now=clock.instant();
        Execution pending=new Execution(UUID.randomUUID(),request.workspaceId(),request.subjectId(),
            request.sourceId(),null,Status.PENDING,now,now);
        repository.create(pending);
        Submission remote=Objects.requireNonNull(executor.submit(plan),"executor returned null");
        if(!repository.transition(pending.id(),pending.workspaceId(),Status.PENDING,Status.SUBMITTED,remote.providerJobId()))
            throw new IllegalStateException("remote accepted but local state conflicted; reconcile "+pending.id());
        return repository.find(pending.id(),pending.workspaceId()).orElseThrow(()->new IllegalStateException("missing execution "+pending.id()));
    }

    public Execution get(UUID id,String workspaceId,String subjectId) {
        Objects.requireNonNull(id); QueryContracts.required(workspaceId,"workspaceId"); QueryContracts.required(subjectId,"subjectId");
        Execution execution=repository.find(id,workspaceId).orElseThrow(()->new IllegalArgumentException("not found"));
        if(!execution.workspaceId().equals(workspaceId)||!execution.subjectId().equals(subjectId))
            throw new IllegalArgumentException("not found");
        return execution;
    }
}
