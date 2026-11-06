package tech.kayys.farabi.quarkus;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import tech.kayys.farabi.engine.DatasetStorageQuotaRepairApplicationService.InapplicablePreviewException;

/** Maps inapplicable quota proposals to HTTP 409. */
@Provider
public class StorageQuotaRepairInapplicableMapper implements ExceptionMapper<InapplicablePreviewException> {
    @Override public Response toResponse(InapplicablePreviewException exception) {
        return Response.status(Response.Status.CONFLICT)
                .entity(new StorageQuotaRepairConflictMapper.Conflict(exception.getMessage())).build();
    }
}