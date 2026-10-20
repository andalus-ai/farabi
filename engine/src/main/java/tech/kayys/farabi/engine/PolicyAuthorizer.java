package tech.kayys.farabi.engine;

import java.util.*;
import tech.kayys.farabi.engine.QueryContracts.*;
import tech.kayys.farabi.engine.QueryPorts.Authorizer;

/** Explicit allowlist for authenticated (workspace, subject, source, dataset) grants. */
public final class PolicyAuthorizer implements Authorizer {
    public record Grant(String workspaceId,String subjectId,String sourceId,String datasetId) {
        public Grant {
            QueryContracts.required(workspaceId,"workspaceId");
            QueryContracts.required(subjectId,"subjectId");
            QueryContracts.required(sourceId,"sourceId");
            QueryContracts.required(datasetId,"datasetId");
        }
    }
    private final Set<Grant> grants;
    public PolicyAuthorizer(Collection<Grant> grants) { this.grants=Set.copyOf(Objects.requireNonNull(grants)); }
    @Override public void authorize(Request request) {
        Objects.requireNonNull(request);
        String[] tokens=request.semanticQuery().split(":",-1);
        if(tokens.length!=2 || !grants.contains(new Grant(request.workspaceId(),request.subjectId(),request.sourceId(),tokens[0])))
            throw new SecurityException("query denied");
    }
}
