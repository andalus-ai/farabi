package tech.kayys.farabi.engine;

import java.time.Clock;
import java.io.InputStream;
import java.util.Objects;
import java.util.UUID;
import java.util.Optional;
import tech.kayys.farabi.engine.DatasetContracts.Dataset;
import tech.kayys.farabi.engine.DatasetContracts.Lifecycle;
import tech.kayys.farabi.engine.DatasetImportJobPorts.*;
import tech.kayys.farabi.engine.DatasetImportPorts.ImportRequest;
import tech.kayys.farabi.engine.DatasetImportPorts.StagedSource;
import tech.kayys.farabi.engine.DatasetImportPorts.UploadSourceIntake;
import tech.kayys.farabi.engine.DatasetStorageAccountingPorts.QuotaStore;
import tech.kayys.farabi.engine.DatasetStorageAccountingPorts.QuotaSubmissionStore;
import tech.kayys.farabi.engine.DatasetStorageLifecyclePorts.ObjectStore;

/** Authorizes, reserves workspace capacity, stages bytes and atomically registers dataset/job/usage. */
public final class DatasetImportSubmissionService {
    private final DatasetPorts.Authorizer authorizer;
    private final DatasetPorts.Catalog catalog;
    private final DatasetImportPorts.SourceIntake intake;
    private final SubmissionStore submissions;
    private final Clock clock;
    private final int maxAttempts;
    private final QuotaStore quota;
    private final QuotaSubmissionStore quotaSubmissions;
    private final ObjectStore objects;

    public DatasetImportSubmissionService(DatasetPorts.Authorizer authorizer, DatasetPorts.Catalog catalog,
            DatasetImportPorts.SourceIntake intake, SubmissionStore submissions, Clock clock, int maxAttempts) {
        this(authorizer,catalog,intake,submissions,clock,maxAttempts,null,null,null);
    }

    public DatasetImportSubmissionService(DatasetPorts.Authorizer authorizer, DatasetPorts.Catalog catalog,
            DatasetImportPorts.SourceIntake intake, SubmissionStore submissions, Clock clock, int maxAttempts,
            QuotaStore quota, QuotaSubmissionStore quotaSubmissions) {
        this(authorizer,catalog,intake,submissions,clock,maxAttempts,quota,quotaSubmissions,null);
    }

    public DatasetImportSubmissionService(DatasetPorts.Authorizer authorizer, DatasetPorts.Catalog catalog,
            DatasetImportPorts.SourceIntake intake, SubmissionStore submissions, Clock clock, int maxAttempts,
            QuotaStore quota, QuotaSubmissionStore quotaSubmissions, ObjectStore objects) {
        this.authorizer=Objects.requireNonNull(authorizer);this.catalog=Objects.requireNonNull(catalog);
        this.intake=Objects.requireNonNull(intake);this.submissions=Objects.requireNonNull(submissions);
        this.clock=Objects.requireNonNull(clock);
        if(maxAttempts<1)throw new IllegalArgumentException("maxAttempts must be positive");
        if ((quota == null) != (quotaSubmissions == null))
            throw new IllegalArgumentException("quota and quota-aware submission store must be configured together");
        this.maxAttempts=maxAttempts;this.quota=quota;this.quotaSubmissions=quotaSubmissions;this.objects=objects;
    }

    public Submission submit(ImportRequest request,String idempotencyKey) {
        return submitInternal(request,idempotencyKey,null);
    }

    /** Submits an HTTP upload using its request-scoped stream; the stream is always closed. */
    public Submission submit(ImportRequest request,String idempotencyKey,InputStream content) {
        Objects.requireNonNull(content,"content");
        try (InputStream upload = content) {
            return submitInternal(request,idempotencyKey,upload);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("unable to close dataset upload stream",e);
        }
    }

    private Submission submitInternal(ImportRequest request,String idempotencyKey,InputStream content) {
        Objects.requireNonNull(request,"request");
        QueryContracts.required(idempotencyKey,"idempotencyKey");
        authorizer.authorizeRegistration(request.workspaceId(),request.subjectId(),request.sourceId());
        Optional<Job> existing=submissions.findByIdempotencyKey(request.workspaceId(),idempotencyKey);
        if(existing.isPresent()){
            Dataset existingDataset=catalog.find(existing.get().datasetId(),request.workspaceId())
                    .orElseThrow(()->new IllegalStateException("idempotent import job references missing dataset"));
            return new Submission(existingDataset,existing.get());
        }

        UUID reservationId = quota == null ? null : UUID.randomUUID();
        if (quota != null) quota.reserve(request.workspaceId(), reservationId,
                Math.max(1L, request.contentLength()), clock.instant());
        boolean committed = false;
        StagedSource staged = null;
        try {
            if (content == null) {
                staged=Objects.requireNonNull(intake.stage(request),"staged source");
            } else {
                if (!(intake instanceof UploadSourceIntake uploadIntake)) {
                    throw new IllegalStateException("configured source intake does not accept upload streams");
                }
                staged=Objects.requireNonNull(uploadIntake.stage(request,content),"staged source");
            }
            var now=clock.instant();
            Dataset dataset=new Dataset(UUID.randomUUID(),request.workspaceId(),request.name(),request.sourceId(),
                    staged.format(),staged.locationRef(),Lifecycle.REGISTERED,now,now);
            NewJob newJob=new NewJob(request.workspaceId(),dataset.id(),idempotencyKey,maxAttempts,now);
            Job job;
            if (quota != null) {
                if (staged.sizeBytes() < 0) throw new IllegalStateException("storage adapter did not report verified object size");
                job=quotaSubmissions.registerAndEnqueue(dataset,newJob,reservationId,staged.sizeBytes());
                committed=true;
                if (!job.datasetId().equals(dataset.id())) deleteUncommittedObject(staged,request.workspaceId());
            } else {
                job=submissions.registerAndEnqueue(dataset,newJob);
            }
            Dataset committedDataset=catalog.find(job.datasetId(),request.workspaceId())
                    .orElseThrow(()->new IllegalStateException("committed import dataset is missing"));
            return new Submission(committedDataset,job);
        } finally {
            try {
                if (!committed && staged != null) deleteUncommittedObject(staged,request.workspaceId());
            } finally {
                if (quota != null && !committed) quota.releaseReservation(reservationId,request.workspaceId(),clock.instant());
            }
        }
    }

    private void deleteUncommittedObject(StagedSource staged,String workspaceId) {
        if (objects != null) objects.delete(staged.locationRef(),workspaceId);
    }

    public record Submission(Dataset dataset,Job job) {
        public Submission {Objects.requireNonNull(dataset);Objects.requireNonNull(job);}
    }
}
