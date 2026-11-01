package tech.kayys.farabi.quarkus;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import tech.kayys.farabi.jdbc.JdbcWorkspaceStorageStore;

/** Exposes only the authenticated workspace's aggregate storage counters. */
@Path("/api/storage/usage")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
public class WorkspaceStorageResource {
    @Inject SecurityIdentity identity;
    @Inject JdbcWorkspaceStorageStore storage;

    @GET
    public StorageUsage get() {
        String workspace=identity.getAttribute("workspace_id");
        if(workspace==null||workspace.isBlank())throw new ForbiddenException("authenticated identity has no workspace scope");
        var usage=storage.usage(workspace).orElseThrow(NotFoundException::new);
        return new StorageUsage(usage.quotaBytes(),usage.usedBytes(),usage.reservedBytes(),usage.availableBytes());
    }

    public record StorageUsage(long quotaBytes,long usedBytes,long reservedBytes,long availableBytes) {}
}
