package tech.kayys.farabi.quarkus;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import tech.kayys.farabi.engine.DatasetStorageQuotaRepairApplicationService.StalePreviewException;

/** Maps stale approval attempts to HTTP 409. */
@Provider
public class StorageQuotaRepairConflictMapper implements ExceptionMapper<StalePreviewException> {
    @Override public Response toResponse(StalePreviewException exception) {
        return Response.status(Response.Status.CONFLICT).entity(new Conflict(exception.getMessage())).build();
    }
    public record Conflict(String error) {}
}