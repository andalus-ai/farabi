package tech.kayys.farabi.quarkus;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import tech.kayys.farabi.engine.DatasetStorageQuotaRepairApplicationService.InapplicablePreviewException;
import tech.kayys.farabi.engine.DatasetStorageQuotaRepairApplicationService.StalePreviewException;

/** Maps quota preview conflicts to HTTP 409 without exposing database details. */
@Provider
public class StorageQuotaRepairConflictMapper implements ExceptionMapper<RuntimeException> {
    @Override public Response toResponse(RuntimeException exception) {
        if (exception instanceof StalePreviewException || exception instanceof InapplicablePreviewException) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(new Conflict(exception.getMessage())).build();
        }
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(new Conflict("quota repair operation failed")).build();
    }
    public record Conflict(String error) {}
}