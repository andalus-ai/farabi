package tech.kayys.farabi.quarkus;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;
import tech.kayys.farabi.engine.DashboardDelivery;
import tech.kayys.farabi.engine.DashboardRuntime;

/**
 * Identity is supplied by a trusted host integration, never by request parameters.
 * Missing identity or dataset adapters deny access rather than using a permissive fallback.
 */
@Path("/api/dashboards")
@ApplicationScoped
public class DashboardDeliveryResource {
 public interface AuthenticatedContext {
  Principal requirePrincipal();
 }
 public record Principal(String workspaceId,String subjectId) {
  public Principal {
   if(workspaceId==null||workspaceId.isBlank()||subjectId==null||subjectId.isBlank())
    throw new SecurityException("missing authenticated identity");
  }
 }
 @Inject Instance<AuthenticatedContext> identity;
 @Inject Instance<DashboardRuntime> runtime;

 @GET
 @Path("/{manifestId}/delivery")
 @Produces(MediaType.APPLICATION_JSON)
 public Response deliver(@PathParam("manifestId") String manifestId) {
  if(identity.isUnsatisfied()||identity.isAmbiguous()||runtime.isUnsatisfied()||runtime.isAmbiguous())
   return Response.status(Response.Status.SERVICE_UNAVAILABLE).build();
  final Principal principal;
  try { principal=identity.get().requirePrincipal(); }
  catch(SecurityException e){ return Response.status(Response.Status.UNAUTHORIZED).build(); }
  if(principal==null)return Response.status(Response.Status.UNAUTHORIZED).build();
  final UUID id;
  try {id=UUID.fromString(manifestId);}
  catch(IllegalArgumentException e){return Response.status(Response.Status.BAD_REQUEST).build();}
  try {
   return Response.ok(DashboardDelivery.from(runtime.get().load(
      id,principal.workspaceId(),principal.subjectId(),1000))).build();
  } catch(SecurityException e) {
   return Response.status(Response.Status.FORBIDDEN).build();
  } catch(IllegalArgumentException e) {
   return Response.status(Response.Status.NOT_FOUND).build();
  }
 }
}
