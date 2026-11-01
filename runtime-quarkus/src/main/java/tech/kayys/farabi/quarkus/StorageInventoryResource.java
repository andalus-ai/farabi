package tech.kayys.farabi.quarkus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import tech.kayys.farabi.engine.DatasetStorageInventoryService;
import tech.kayys.farabi.engine.DatasetStorageInventoryService.FindingType;
import tech.kayys.farabi.engine.DatasetStorageOperatorAuthorizer;

/** Operator-only inventory inspection and history. The default authorizer denies all requests. */
@Path("/api/storage/inventory")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
public class StorageInventoryResource {
    @Inject SecurityIdentity identity;
    @Inject DatasetStorageInventoryService inventory;
    @Inject DatasetStorageOperatorAuthorizer authorizer;

    @POST
    public InventoryReport inspect() {
        String workspace = workspace();
        authorize(workspace);
        return map(inventory.inspect(workspace));
    }

    @GET
    public List<InventoryReport> recent(@QueryParam("limit") Integer requestedLimit) {
        String workspace = workspace();
        authorize(workspace);
        int limit = requestedLimit == null ? 20 : requestedLimit;
        return inventory.recent(workspace, limit).stream().map(this::map).toList();
    }

    private String workspace() {
        String workspace = identity.getAttribute("workspace_id");
        if (workspace == null || workspace.isBlank())
            throw new ForbiddenException("authenticated identity has no workspace scope");
        return workspace;
    }

    private void authorize(String workspace) {
        String subject = identity.getPrincipal() == null ? null : identity.getPrincipal().getName();
        if (subject == null || subject.isBlank()) throw new ForbiddenException("authenticated subject is required");
        authorizer.authorize(subject, workspace);
    }

    private InventoryReport map(DatasetStorageInventoryService.Report report) {
        List<FindingView> findings = report.findings().stream()
                .map(f -> new FindingView(f.type(), f.locationRef(), f.expectedBytes(), f.actualBytes(),
                        f.objectLastModified())).toList();
        return new InventoryReport(report.id(), report.workspaceId(), report.inspectedAt(),
                report.orphanGracePeriod().toSeconds(), report.catalogObjects(), report.providerObjects(),
                report.consistent(), findings);
    }

    public record InventoryReport(UUID id, String workspaceId, Instant inspectedAt, long orphanGraceSeconds,
            int catalogObjects, int providerObjects, boolean consistent, List<FindingView> findings) {}
    public record FindingView(FindingType type, String locationRef, long expectedBytes, long actualBytes,
            Instant objectLastModified) {}
}
