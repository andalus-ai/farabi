package tech.kayys.farabi.quarkus;

import java.util.List;
import java.util.UUID;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import tech.kayys.farabi.engine.DatasetStorageOperatorAuthorizer;
import tech.kayys.farabi.engine.DatasetStorageQuotaRepairPreviewService;
import tech.kayys.farabi.engine.DatasetStorageQuotaRepairPreviewService.Plan;

/** Operator-only, workspace-scoped quota repair proposals. No mutation endpoint is exposed. */
@Path("/api/storage/usage/repair-previews")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
public class StorageQuotaRepairPreviewResource {
    @Inject SecurityIdentity identity;
    @Inject DatasetStorageQuotaRepairPreviewService previews;
    @Inject DatasetStorageOperatorAuthorizer authorizer;

    @POST
    public Response create() {
        String workspace=workspace(); authorize(workspace);
        Plan plan=previews.preview(workspace,identity.getPrincipal().getName());
        return Response.status(Response.Status.CREATED).entity(plan).build();
    }

    @GET
    public List<Plan> recent(@QueryParam("limit") Integer requestedLimit) {
        String workspace=workspace(); authorize(workspace);
        return previews.recent(workspace,requestedLimit==null?20:requestedLimit);
    }

    @GET @Path("/{planId}")
    public Plan get(@PathParam("planId") UUID id) {
        String workspace=workspace(); authorize(workspace);
        return previews.find(id,workspace).orElseThrow(NotFoundException::new);
    }

    private String workspace() {
        String value=identity.getAttribute("workspace_id");
        if(value==null||value.isBlank()) throw new ForbiddenException("authenticated identity has no workspace scope");
        return value;
    }
    private void authorize(String workspace) {
        String subject=identity.getPrincipal()==null?null:identity.getPrincipal().getName();
        if(subject==null||subject.isBlank()) throw new ForbiddenException("authenticated subject is required");
        authorizer.authorize(subject,workspace);
    }
}