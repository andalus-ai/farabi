package tech.kayys.farabi.quarkus;

import java.time.Duration;
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
import org.eclipse.microprofile.config.inject.ConfigProperty;
import tech.kayys.farabi.engine.DatasetStorageOperatorAuthorizer;
import tech.kayys.farabi.engine.DatasetDeletionApprovalAuthorizer;
import tech.kayys.farabi.jdbc.JdbcDatasetDeletionApprovalStore;
import tech.kayys.farabi.jdbc.JdbcDatasetDeletionApprovalStore.Approval;

/** Workspace-scoped maker-checker API for dataset deletion. */
@Path("/api/dataset-deletion-approvals")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DatasetDeletionApprovalResource {
    @Inject SecurityIdentity identity;
    @Inject DataSource dataSource;
    @Inject DatasetStorageOperatorAuthorizer requestAuthorizer;
    @Inject DatasetDeletionApprovalAuthorizer approvalAuthorizer;
    @ConfigProperty(name="farabi.dataset.deletion.approval-lifetime",defaultValue="PT24H")
    Duration approvalLifetime;
    @ConfigProperty(name="farabi.dataset.deletion.max-attempts",defaultValue="5")
    int maxAttempts;

    @POST
    public Response request(RequestBody body) {
        String ws=workspace();String actor=subject();requestAuthorizer.authorize(actor,ws);
        if(body==null||body.datasetId()==null||body.reason()==null||body.reason().isBlank())
            throw new BadRequestException("datasetId and reason are required");
        Approval result=store().request(body.datasetId(),ws,actor,body.reason(),Instant.now(),approvalLifetime);
        return Response.status(Response.Status.CREATED).entity(result).build();
    }

    @POST @Path("/{approvalId}/approve")
    public Approval approve(@PathParam("approvalId") UUID id,DecisionBody body) {
        String ws=workspace();String actor=subject();approvalAuthorizer.authorizeApproval(actor,ws);
        if(body==null||body.reason()==null||body.reason().isBlank())throw new BadRequestException("approval reason is required");
        try{return store().approve(id,ws,actor,body.reason(),Instant.now(),maxAttempts);}
        catch(java.util.NoSuchElementException missing){throw new NotFoundException();}
    }

    @POST @Path("/{approvalId}/reject")
    public Approval reject(@PathParam("approvalId") UUID id,DecisionBody body) {
        String ws=workspace();String actor=subject();approvalAuthorizer.authorizeApproval(actor,ws);
        if(body==null||body.reason()==null||body.reason().isBlank())throw new BadRequestException("rejection reason is required");
        try{return store().reject(id,ws,actor,body.reason(),Instant.now());}
        catch(java.util.NoSuchElementException missing){throw new NotFoundException();}
    }

    @GET
    public List<Approval> recent(@QueryParam("limit") Integer limit) {
        String ws=workspace();requestAuthorizer.authorize(subject(),ws);
        return store().recent(ws,limit==null?50:limit);
    }

    @GET @Path("/{approvalId}")
    public Approval get(@PathParam("approvalId") UUID id) {
        String ws=workspace();requestAuthorizer.authorize(subject(),ws);
        return store().find(id,ws).orElseThrow(NotFoundException::new);
    }

    private JdbcDatasetDeletionApprovalStore store(){return new JdbcDatasetDeletionApprovalStore(dataSource);}
    private String workspace(){String ws=identity.getAttribute("workspace_id");if(ws==null||ws.isBlank())throw new ForbiddenException("authenticated identity has no workspace scope");return ws;}
    private String subject(){String s=identity.getPrincipal()==null?null:identity.getPrincipal().getName();if(s==null||s.isBlank())throw new ForbiddenException("authenticated subject is required");return s;}
    public record RequestBody(UUID datasetId,String reason) {}
    public record DecisionBody(String reason) {}
}
