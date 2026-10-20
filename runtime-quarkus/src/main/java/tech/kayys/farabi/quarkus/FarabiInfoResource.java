package tech.kayys.farabi.quarkus;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;

/** Informational endpoint; not a database or Dremio readiness check. */
@Path("/farabi")
public class FarabiInfoResource {
    @GET @Path("/info") @Produces(MediaType.APPLICATION_JSON)
    public Map<String,Object> info() {
        return Map.of("name","Farabi","runtime","quarkus","governedQueryApiEnabled",false);
    }
}
