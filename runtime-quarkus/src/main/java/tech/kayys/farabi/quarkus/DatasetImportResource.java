package tech.kayys.farabi.quarkus;

import java.util.UUID;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import io.quarkus.security.identity.SecurityIdentity;
import tech.kayys.farabi.engine.DatasetImportJobPorts.Job;
import tech.kayys.farabi.jdbc.JdbcDatasetImportJobStore;

/**
 * Workspace-scoped read API for durable dataset import jobs.
 *
 * The workspace claim is supplied by the configured identity provider. Missing claims
 * fail closed; a workspace identifier is never accepted from a query or path parameter.
 */
@Path("/api/dataset-imports")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
public class DatasetImportResource {
    @Inject SecurityIdentity identity;
    @Inject JdbcDatasetImportJobStore jobs;

    @GET
    @Path("/{jobId}")
    public ImportJobResponse get(@PathParam("jobId") UUID jobId) {
        String workspaceId = identity.getAttribute("workspace_id");
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new jakarta.ws.rs.ForbiddenException("authenticated identity has no workspace scope");
        }
        Job job = jobs.find(jobId, workspaceId).orElseThrow(NotFoundException::new);
        return ImportJobResponse.from(job);
    }

    public record ImportJobResponse(
            UUID id, UUID datasetId, String status, int attempts, int maxAttempts,
            String errorCode, String errorMessage, java.time.Instant createdAt,
            java.time.Instant updatedAt) {
        static ImportJobResponse from(Job job) {
            return new ImportJobResponse(job.id(), job.datasetId(), job.status().name(),
                    job.attempts(), job.maxAttempts(), job.errorCode(), job.errorMessage(),
                    job.createdAt(), job.updatedAt());
        }
    }
}
