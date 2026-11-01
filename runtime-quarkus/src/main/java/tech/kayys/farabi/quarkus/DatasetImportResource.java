package tech.kayys.farabi.quarkus;

import java.util.UUID;
import java.nio.file.Files;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import io.quarkus.security.identity.SecurityIdentity;
import tech.kayys.farabi.engine.DatasetImportJobPorts.Job;
import tech.kayys.farabi.engine.DatasetContracts.Format;
import tech.kayys.farabi.engine.DatasetImportPorts.ImportRequest;
import tech.kayys.farabi.engine.DatasetImportSubmissionService;
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
    @Inject DatasetImportSubmissionService submissions;


    /** Accepts a multipart file; workspace and subject are derived only from the authenticated identity. */
    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response submit(
            @RestForm("file") FileUpload file,
            @RestForm("datasetName") String datasetName,
            @RestForm("sourceId") String sourceId,
            @RestForm("format") String format,
            @HeaderParam("Idempotency-Key") String idempotencyKey) {
        if (file == null) throw new jakarta.ws.rs.BadRequestException("file is required");
        String workspaceId = identity.getAttribute("workspace_id");
        String subjectId = identity.getPrincipal() == null ? null : identity.getPrincipal().getName();
        if (workspaceId == null || workspaceId.isBlank() || subjectId == null || subjectId.isBlank()) {
            throw new jakarta.ws.rs.ForbiddenException("authenticated identity is missing workspace or subject scope");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new jakarta.ws.rs.BadRequestException("Idempotency-Key header is required");
        }
        final Format parsedFormat;
        try {
            parsedFormat = Format.valueOf(format == null ? "" : format.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new jakarta.ws.rs.BadRequestException("unsupported dataset format");
        }
        if (parsedFormat != Format.CSV && parsedFormat != Format.TSV
                && parsedFormat != Format.JSON && parsedFormat != Format.JSONL) {
            throw new jakarta.ws.rs.BadRequestException("supported formats are CSV, TSV, JSON and JSONL");
        }
        if (datasetName == null || datasetName.isBlank() || sourceId == null || sourceId.isBlank()) {
            throw new jakarta.ws.rs.BadRequestException("datasetName and sourceId are required");
        }
        String filename = file.fileName() == null || file.fileName().isBlank() ? "upload" : file.fileName();
        String contentType = file.contentType() == null ? "application/octet-stream" : file.contentType();
        ImportRequest request = new ImportRequest(workspaceId, subjectId, datasetName, sourceId,
                parsedFormat, filename, file.size(), contentType);
        try {
            var submission = submissions.submit(request, idempotencyKey, Files.newInputStream(file.uploadedFile()));
            return Response.accepted(new ImportSubmissionResponse(submission.job().id(),
                    submission.dataset().id(), submission.job().status().name())).build();
        } catch (java.io.IOException e) {
            throw new jakarta.ws.rs.BadRequestException("unable to read uploaded file", e);
        }
    }

    public record ImportSubmissionResponse(UUID jobId, UUID datasetId, String status) {}

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
