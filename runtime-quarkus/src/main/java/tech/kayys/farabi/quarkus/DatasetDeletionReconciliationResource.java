package tech.kayys.farabi.quarkus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import tech.kayys.farabi.engine.DatasetDeletionReconciliationService;
import tech.kayys.farabi.engine.DatasetDeletionReconciliationService.Finding;
import tech.kayys.farabi.engine.DatasetDeletionReconciliationService.Report;
import tech.kayys.farabi.engine.DatasetStorageOperatorAuthorizer;

/** Operator-triggered, read-only reconciliation of provider deletion state and quota accounting. */
@Path("/api/storage/deletion-reconciliation")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
public class DatasetDeletionReconciliationResource {
    @Inject SecurityIdentity identity;
    @Inject DatasetDeletionReconciliationService reconciliation;
    @Inject DatasetStorageOperatorAuthorizer authorizer;

    @POST
    public Response inspect() {
        String workspace=workspace();authorize(workspace);
        Report report=reconciliation.inspect(workspace);
        return Response.ok(map(report)).build();
    }

    @GET
    public List<ReportView> recent(@QueryParam("limit") Integer requestedLimit) {
        String workspace=workspace();authorize(workspace);
        return reconciliation.recent(workspace,requestedLimit==null?20:requestedLimit).stream().map(this::map).toList();
    }

    private String workspace(){
        String workspace=identity.getAttribute("workspace_id");
        if(workspace==null||workspace.isBlank())throw new ForbiddenException("authenticated identity has no workspace scope");
        return workspace;
    }
    private void authorize(String workspace){
        String subject=identity.getPrincipal()==null?null:identity.getPrincipal().getName();
        if(subject==null||subject.isBlank())throw new ForbiddenException("authenticated subject is required");
        authorizer.authorize(subject,workspace);
    }
    private ReportView map(Report r){
        return new ReportView(r.id(),r.workspaceId(),r.inspectedAt(),r.datasetsInspected(),
                r.deletionJobsInspected(),r.consistent(),r.findings());
    }
    public record ReportView(UUID id,String workspaceId,Instant inspectedAt,int datasetsInspected,
            int deletionJobsInspected,boolean consistent,List<Finding> findings) {}
}
