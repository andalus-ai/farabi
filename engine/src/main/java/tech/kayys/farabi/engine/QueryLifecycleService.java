package tech.kayys.farabi.engine;

import java.util.Objects;
import java.util.UUID;
import tech.kayys.farabi.engine.QueryContracts.*;
import tech.kayys.farabi.engine.QueryLifecycle.*;

/**
 * Authenticated workspace and subject are provided by the trusted caller.
 * All provider operations first resolve a workspace-scoped local execution.
 */
public final class QueryLifecycleService {
    private final QueryPorts.ExecutionRepository repository;
    private final Provider provider;
    public QueryLifecycleService(QueryPorts.ExecutionRepository repository,Provider provider) {
        this.repository=Objects.requireNonNull(repository);this.provider=Objects.requireNonNull(provider);
    }
    public RemoteState status(UUID id,String workspace,String subject) {
        Execution e=authorized(id,workspace,subject);
        if(e.providerJobId()==null) throw new IllegalStateException("remote job not yet known; reconcile pending submission");
        return provider.status(e.providerJobId());
    }
    public ResultPage results(UUID id,String workspace,String subject,int offset,int limit) {
        if(offset<0 || limit<1 || limit>1000) throw new IllegalArgumentException("invalid page range");
        Execution e=authorized(id,workspace,subject);
        if(e.status()!=Status.SUCCEEDED) throw new IllegalStateException("results unavailable until local success");
        if(e.providerJobId()==null) throw new IllegalStateException("missing provider job id");
        return provider.results(e.providerJobId(),offset,limit);
    }
    public void cancel(UUID id,String workspace,String subject) {
        Execution e=authorized(id,workspace,subject);
        if(e.status()==Status.SUCCEEDED||e.status()==Status.FAILED||e.status()==Status.CANCELLED)
            throw new IllegalStateException("execution already terminal");
        if(e.providerJobId()==null) throw new IllegalStateException("cannot cancel ambiguous pending submission automatically");
        provider.cancel(e.providerJobId());
        // Cancellation is asynchronous; a worker must reconcile provider state.
    }
    private Execution authorized(UUID id,String workspace,String subject) {
        Objects.requireNonNull(id);QueryContracts.required(workspace,"workspace");QueryContracts.required(subject,"subject");
        Execution e=repository.find(id,workspace).orElseThrow(()->new IllegalArgumentException("not found"));
        if(!e.subjectId().equals(subject)) throw new IllegalArgumentException("not found");
        return e;
    }
}
