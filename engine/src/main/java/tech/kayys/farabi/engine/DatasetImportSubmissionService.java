package tech.kayys.farabi.engine;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.Optional;
import tech.kayys.farabi.engine.DatasetContracts.Dataset;
import tech.kayys.farabi.engine.DatasetContracts.Lifecycle;
import tech.kayys.farabi.engine.DatasetImportJobPorts.*;
import tech.kayys.farabi.engine.DatasetImportPorts.ImportRequest;
import tech.kayys.farabi.engine.DatasetImportPorts.StagedSource;

/** Authorizes and stages an upload, registers its dataset, then durably queues profiling. */
public final class DatasetImportSubmissionService {
    private final DatasetPorts.Authorizer authorizer;
    private final DatasetPorts.Catalog catalog;
    private final DatasetImportPorts.SourceIntake intake;
    private final Store jobs;
    private final Clock clock;
    private final int maxAttempts;

    public DatasetImportSubmissionService(DatasetPorts.Authorizer authorizer, DatasetPorts.Catalog catalog,
            DatasetImportPorts.SourceIntake intake, Store jobs, Clock clock, int maxAttempts) {
        this.authorizer=Objects.requireNonNull(authorizer);this.catalog=Objects.requireNonNull(catalog);
        this.intake=Objects.requireNonNull(intake);this.jobs=Objects.requireNonNull(jobs);
        this.clock=Objects.requireNonNull(clock);
        if(maxAttempts<1)throw new IllegalArgumentException("maxAttempts must be positive");
        this.maxAttempts=maxAttempts;
    }

    public Submission submit(ImportRequest request,String idempotencyKey) {
        Objects.requireNonNull(request,"request");
        QueryContracts.required(idempotencyKey,"idempotencyKey");
        authorizer.authorizeRegistration(request.workspaceId(),request.subjectId(),request.sourceId());
        Optional<Job> existing=jobs.findByIdempotencyKey(request.workspaceId(),idempotencyKey);
        if(existing.isPresent()){
            Dataset existingDataset=catalog.find(existing.get().datasetId(),request.workspaceId())
                    .orElseThrow(()->new IllegalStateException("idempotent import job references missing dataset"));
            return new Submission(existingDataset,existing.get());
        }
        StagedSource staged=Objects.requireNonNull(intake.stage(request),"staged source");
        var now=clock.instant();
        Dataset dataset=new Dataset(UUID.randomUUID(),request.workspaceId(),request.name(),request.sourceId(),
                staged.format(),staged.locationRef(),Lifecycle.REGISTERED,now,now);
        if(!catalog.create(dataset))throw new IllegalStateException("dataset name already exists in workspace");
        Job job=jobs.enqueue(new NewJob(request.workspaceId(),dataset.id(),idempotencyKey,maxAttempts,now));
        return new Submission(dataset,job);
    }

    public record Submission(Dataset dataset,Job job) {
        public Submission {Objects.requireNonNull(dataset);Objects.requireNonNull(job);}
    }
}
