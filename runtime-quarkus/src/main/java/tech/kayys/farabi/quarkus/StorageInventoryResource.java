package tech.kayys.farabi.quarkus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import tech.kayys.farabi.engine.DatasetStorageInventoryJobPorts;
import tech.kayys.farabi.engine.DatasetStorageInventoryJobPorts.Job;
import tech.kayys.farabi.engine.DatasetStorageInventoryService;
import tech.kayys.farabi.engine.DatasetStorageCleanupPreviewService;
import tech.kayys.farabi.engine.DatasetStorageCleanupPreviewService.Plan;
import tech.kayys.farabi.engine.DatasetStorageInventoryService.FindingType;
import tech.kayys.farabi.engine.DatasetStorageOperatorAuthorizer;
import tech.kayys.farabi.engine.DatasetStorageInventoryAuditPorts.AuditLog;
import tech.kayys.farabi.engine.DatasetStorageInventoryAuditPorts.Event;
import tech.kayys.farabi.engine.DatasetStorageInventoryAuditPorts.EventType;

/** Operator-only asynchronous inventory API. Workspace is always derived from the authenticated identity. */
@Path("/api/storage/inventory")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
public class StorageInventoryResource {
    @Inject SecurityIdentity identity;
    @Inject DatasetStorageInventoryService inventory;
    @Inject DatasetStorageCleanupPreviewService cleanupPreviews;
    @Inject DatasetStorageInventoryJobPorts.Store jobs;
    @Inject DatasetStorageOperatorAuthorizer authorizer;
    @Inject AuditLog audit;
    @ConfigProperty(name="farabi.storage.inventory.max-attempts", defaultValue="4") int maxAttempts;

    @POST
    public Response submit(@HeaderParam("Idempotency-Key") String idempotencyKey) {
        String workspace = workspace(); authorize(workspace);
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 200)
            return Response.status(400).entity(new ErrorView("Idempotency-Key header is required (max 200 characters)")).build();
        String subject = identity.getPrincipal().getName();
        Job job = jobs.enqueue(workspace, idempotencyKey, maxAttempts, Instant.now());
        audit.append(new Event(UUID.randomUUID(), workspace, job.id(), subject, EventType.SUBMITTED,
                0, null, null, Instant.now()));
        return Response.accepted(map(job)).build();
    }

    @GET
    @Path("/{jobId}")
    public JobView status(@PathParam("jobId") UUID jobId) {
        String workspace = workspace(); authorize(workspace);
        return jobs.find(jobId, workspace).map(this::map).orElseThrow(jakarta.ws.rs.NotFoundException::new);
    }

    @GET
    @Path("/audit")
    public List<AuditEventView> auditHistory(@QueryParam("jobId") UUID jobId, @QueryParam("limit") Integer requestedLimit) {
        String workspace = workspace(); authorize(workspace);
        int limit = requestedLimit == null ? 50 : requestedLimit;
        return audit.recent(workspace, jobId, limit).stream().map(e -> new AuditEventView(
                e.id(), e.jobId(), e.actorId(), e.type(), e.attempt(), e.reportId(), e.errorCode(), e.occurredAt())).toList();
    }

    @GET
    public List<InventoryReport> recent(@QueryParam("limit") Integer requestedLimit) {
        String workspace = workspace(); authorize(workspace);
        int limit = requestedLimit == null ? 20 : requestedLimit;
        return inventory.recent(workspace, limit).stream().map(this::map).toList();
    }

    /** Creates a persisted dry-run proposal only; this endpoint cannot delete objects. */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/cleanup-previews")
    public Response previewCleanup(CleanupPreviewRequest request) {
        String workspace = workspace(); authorize(workspace);
        if (request == null || request.reportId() == null)
            return Response.status(400).entity(new ErrorView("reportId is required")).build();
        String subject = identity.getPrincipal().getName();
        try {
            Plan plan = cleanupPreviews.preview(workspace, request.reportId(), subject);
            return Response.status(Response.Status.CREATED).entity(plan).build();
        } catch (IllegalArgumentException missing) {
            throw new jakarta.ws.rs.NotFoundException("inventory report not found in workspace");
        }
    }

    @GET
    @Path("/cleanup-previews")
    public List<Plan> cleanupPreviewHistory(@QueryParam("limit") Integer requestedLimit) {
        String workspace = workspace(); authorize(workspace);
        return cleanupPreviews.recent(workspace, requestedLimit == null ? 20 : requestedLimit);
    }

    @GET
    @Path("/cleanup-previews/{planId}")
    public Plan cleanupPreview(@PathParam("planId") UUID planId) {
        String workspace = workspace(); authorize(workspace);
        return cleanupPreviews.find(planId, workspace).orElseThrow(jakarta.ws.rs.NotFoundException::new);
    }

    private String workspace() {
        String workspace = identity.getAttribute("workspace_id");
        if (workspace == null || workspace.isBlank()) throw new ForbiddenException("authenticated identity has no workspace scope");
        return workspace;
    }
    private void authorize(String workspace) {
        String subject = identity.getPrincipal() == null ? null : identity.getPrincipal().getName();
        if (subject == null || subject.isBlank()) throw new ForbiddenException("authenticated subject is required");
        authorizer.authorize(subject, workspace);
    }
    private JobView map(Job j) {
        return new JobView(j.id(), j.workspaceId(), j.status(), j.attempts(), j.maxAttempts(),
                j.reportId(), j.errorCode(), j.errorMessage(), j.createdAt(), j.updatedAt());
    }
    private InventoryReport map(DatasetStorageInventoryService.Report report) {
        List<FindingView> findings = report.findings().stream()
                .map(f -> new FindingView(f.type(), f.locationRef(), f.expectedBytes(), f.actualBytes(), f.objectLastModified())).toList();
        return new InventoryReport(report.id(), report.workspaceId(), report.inspectedAt(),
                report.orphanGracePeriod().toSeconds(), report.catalogObjects(), report.providerObjects(),
                report.consistent(), findings);
    }
    public record CleanupPreviewRequest(UUID reportId) {}
    public record AuditEventView(UUID id, UUID jobId, String actorId, EventType type, int attempt, UUID reportId, String errorCode, Instant occurredAt) {}
    public record JobView(UUID id, String workspaceId, DatasetStorageInventoryJobPorts.Status status,
            int attempts, int maxAttempts, UUID reportId, String errorCode, String errorMessage,
            Instant createdAt, Instant updatedAt) {}
    public record ErrorView(String message) {}
    public record InventoryReport(UUID id, String workspaceId, Instant inspectedAt, long orphanGraceSeconds,
            int catalogObjects, int providerObjects, boolean consistent, List<FindingView> findings) {}
    public record FindingView(FindingType type, String locationRef, long expectedBytes, long actualBytes,
            Instant objectLastModified) {}
}
