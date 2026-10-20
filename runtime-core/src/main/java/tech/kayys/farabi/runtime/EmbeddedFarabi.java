package tech.kayys.farabi.runtime;

import java.time.Clock;
import tech.kayys.farabi.engine.DurableQueryService;
import tech.kayys.farabi.engine.QueryPorts.*;

/** Runtime-neutral composition entrypoint for embedding in any Java application. */
public final class EmbeddedFarabi {
    private final DurableQueryService queries;
    public EmbeddedFarabi(Authorizer authorizer,Compiler compiler,Executor executor,ExecutionRepository repository,Clock clock) {
        this.queries=new DurableQueryService(authorizer,compiler,executor,repository,clock);
    }
    public DurableQueryService queries() { return queries; }
}
