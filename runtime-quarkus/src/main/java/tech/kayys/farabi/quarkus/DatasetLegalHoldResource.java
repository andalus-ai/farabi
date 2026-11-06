package tech.kayys.farabi.quarkus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import javax.sql.DataSource;
import tech.kayys.farabi.engine.DatasetStorageOperatorAuthorizer;
import tech.kayys.farabi.jdbc.JdbcDatasetLegalHoldStore;
import tech.kayys.farabi.jdbc.JdbcDatasetLegalHoldStore.Hold;

/** Operator-governed legal holds. Workspace scope is always taken from the authenticated identity. */
@Path("/api/storage/legal-holds")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DatasetLegalHoldResource {
    @Inject SecurityIdentity identity;
    @Inject DataSource dataSource;
    @Inject DatasetStorageOperatorAuthorizer authorizer;

    @POST
    @Path("/datasets/{datasetId}")
    public Response place(@PathParam("datasetId") UUID datasetId, HoldRequest request) {
        String workspace=workspace(); authorize(workspace);
        if(request==null||request.reason()==null||request.reason().isBlank())
            return Response.status(400).entity(new ErrorView("reason is required")).build();
        Hold hold=store().place(datasetId,workspace,subject(),request.reason(),Instant.now());
        return Response.status(Response.Status.CREATED).entity(hold).build();
    }

    @POST
    @Path("/{holdId}/release")
    public Hold release(@PathParam("holdId") UUID holdId, HoldRequest request) {
        String workspace=workspace(); authorize(workspace);
        if(request==null||request.reason()==null||request.reason().isBlank())
            throw new BadRequestException("release reason is required");
        try { return store().release(holdId,workspace,subject(),request.reason(),Instant.now()); }
        catch(java.util.NoSuchElementException missing) { throw new NotFoundException(); }
    }

    @GET
    public List<Hold> recent(@QueryParam("datasetId") UUID datasetId,@QueryParam("limit") Integer requestedLimit) {
        String workspace=workspace(); authorize(workspace);
        return store().recent(workspace,datasetId,requestedLimit==null?50:requestedLimit);
    }

    private JdbcDatasetLegalHoldStore store(){return new JdbcDatasetLegalHoldStore(dataSource);}
    private String workspace(){
        String value=identity.getAttribute("workspace_id");
        if(value==null||value.isBlank())throw new ForbiddenException("authenticated identity has no workspace scope");
        return value;
    }
    private String subject(){
        String value=identity.getPrincipal()==null?null:identity.getPrincipal().getName();
        if(value==null||value.isBlank())throw new ForbiddenException("authenticated subject is required");
        return value;
    }
    private void authorize(String workspace){authorizer.authorize(subject(),workspace);}
    public record HoldRequest(String reason) {}
    public record ErrorView(String message) {}
}
