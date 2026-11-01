package tech.kayys.farabi.quarkus;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import tech.kayys.farabi.jdbc.JdbcWorkspaceStorageStore.StorageQuotaExceededException;

/** Maps workspace quota rejection to a stable client error instead of an internal server error. */
@Provider
public class StorageQuotaExceededMapper implements ExceptionMapper<StorageQuotaExceededException> {
    @Override public Response toResponse(StorageQuotaExceededException exception) {
        return Response.status(413).type(MediaType.APPLICATION_JSON)
                .entity(new ErrorResponse("STORAGE_QUOTA_EXCEEDED", exception.getMessage())).build();
    }
    public record ErrorResponse(String code,String message) {}
}
