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
import tech.kayys.farabi.jdbc.JdbcDatasetRetentionStore;
import tech.kayys.farabi.jdbc.JdbcDatasetRetentionStore.*;

/** Operator-only retention policy, exemption and read-only preview API. */
@Path("/api/storage/retention")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DatasetRetentionResource {
    @Inject SecurityIdentity identity;
    @Inject DataSource dataSource;
    @Inject DatasetStorageOperatorAuthorizer authorizer;

    @POST @Path("/policies")
    public Response createPolicy(PolicyRequest request) {
        String ws=workspace();authorize(ws);
        if(request==null||request.retentionDays()<1||request.reason()==null||request.reason().isBlank()||(request.sourceId()!=null&&request.sourceId().isBlank()))
            throw new BadRequestException("retentionDays and reason are required");
        Instant effective=request.effectiveAt()==null?Instant.now():request.effectiveAt();
        Policy policy=store().createPolicy(ws,request.sourceId(),request.retentionDays(),effective,subject(),request.reason(),Instant.now());
        return Response.status(Response.Status.CREATED).entity(policy).build();
    }

    @GET @Path("/policies")
    public List<Policy> policies(@QueryParam("limit") Integer limit) {
        String ws=workspace();authorize(ws);return store().policies(ws,limit==null?50:limit);
    }

    @POST @Path("/datasets/{datasetId}/exemptions")
    public Response exempt(@PathParam("datasetId") UUID datasetId,ExemptionRequest request) {
        String ws=workspace();authorize(ws);
        if(request==null||request.reason()==null||request.reason().isBlank())throw new BadRequestException("reason is required");
        try {
            Exemption exemption=store().createExemption(datasetId,ws,subject(),request.reason(),request.expiresAt(),Instant.now());
            return Response.status(Response.Status.CREATED).entity(exemption).build();
        } catch(java.util.NoSuchElementException missing) { throw new NotFoundException(); }
    }

    @POST @Path("/exemptions/{exemptionId}/revoke")
    public Exemption revoke(@PathParam("exemptionId") UUID id,ReasonRequest request) {
        String ws=workspace();authorize(ws);
        if(request==null||request.reason()==null||request.reason().isBlank())throw new BadRequestException("reason is required");
        try{return store().revokeExemption(id,ws,subject(),request.reason(),Instant.now());}
        catch(java.util.NoSuchElementException missing){throw new NotFoundException();}
    }

    /** Read-only: shows datasets whose retention clock has elapsed; legal holds remain visibly blocked. */
    @GET @Path("/preview")
    public List<Candidate> preview(@QueryParam("limit") Integer limit) {
        String ws=workspace();authorize(ws);return store().preview(ws,Instant.now(),limit==null?100:limit);
    }

    private JdbcDatasetRetentionStore store(){return new JdbcDatasetRetentionStore(dataSource);}
    private String workspace(){String ws=identity.getAttribute("workspace_id");if(ws==null||ws.isBlank())throw new ForbiddenException("authenticated identity has no workspace scope");return ws;}
    private String subject(){String s=identity.getPrincipal()==null?null:identity.getPrincipal().getName();if(s==null||s.isBlank())throw new ForbiddenException("authenticated subject is required");return s;}
    private void authorize(String ws){authorizer.authorize(subject(),ws);}
    public record PolicyRequest(String sourceId,int retentionDays,Instant effectiveAt,String reason) {}
    public record ExemptionRequest(String reason,Instant expiresAt) {}
    public record ReasonRequest(String reason) {}
}
