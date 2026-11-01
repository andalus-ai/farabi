package tech.kayys.farabi.quarkus;

import java.util.UUID;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import tech.kayys.farabi.engine.DatasetDeletionService;

/** Authenticated dataset lifecycle API. Workspace is always derived from identity claims. */
@Path("/api/datasets")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
public class DatasetResource {
    @Inject SecurityIdentity identity;
    @Inject DatasetDeletionService deletions;

    @DELETE
    @Path("/{datasetId}")
    public Response delete(@PathParam("datasetId") UUID datasetId) {
        String workspace = identity.getAttribute("workspace_id");
        String subject = identity.getPrincipal() == null ? null : identity.getPrincipal().getName();
        if (workspace == null || workspace.isBlank() || subject == null || subject.isBlank())
            throw new jakarta.ws.rs.ForbiddenException("authenticated identity is missing workspace or subject scope");
        var job = deletions.request(datasetId, workspace, subject)
                .orElseThrow(NotFoundException::new);
        return Response.accepted(new DeletionResponse(job.id(), datasetId, job.status())).build();
    }

    public record DeletionResponse(UUID deletionJobId, UUID datasetId, String status) {}
}
