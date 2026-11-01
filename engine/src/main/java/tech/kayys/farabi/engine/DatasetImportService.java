package tech.kayys.farabi.engine;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import tech.kayys.farabi.engine.DatasetContracts.Dataset;
import tech.kayys.farabi.engine.DatasetContracts.Lifecycle;
import tech.kayys.farabi.engine.DatasetContracts.Profile;
import tech.kayys.farabi.engine.DatasetImportPorts.ImportRequest;
import tech.kayys.farabi.engine.DatasetImportPorts.StagedSource;

/**
 * Coordinates authorized intake, durable registration and profiling.
 * The service owns orchestration; storage and profiling remain replaceable adapters.
 */
public final class DatasetImportService {
    private final DatasetPorts.Authorizer authorizer;
    private final DatasetPorts.Catalog catalog;
    private final DatasetImportPorts.SourceIntake intake;
    private final DatasetImportPorts.Profiler profiler;
    private final Clock clock;

    public DatasetImportService(
            DatasetPorts.Authorizer authorizer,
            DatasetPorts.Catalog catalog,
            DatasetImportPorts.SourceIntake intake,
            DatasetImportPorts.Profiler profiler,
            Clock clock) {
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.intake = Objects.requireNonNull(intake, "intake");
        this.profiler = Objects.requireNonNull(profiler, "profiler");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Returns only after the dataset and its initial profile have been durably saved. */
    public ImportedDataset importAndProfile(ImportRequest request) {
        Objects.requireNonNull(request, "request");
        authorizer.authorizeRegistration(request.workspaceId(), request.subjectId(), request.sourceId());

        // Authorization deliberately precedes any intake operation that may persist uploaded bytes.
        StagedSource staged = Objects.requireNonNull(intake.stage(request), "staged source");
        Dataset dataset = new Dataset(
                UUID.randomUUID(), request.workspaceId(), request.name(), request.sourceId(),
                staged.format(), staged.locationRef(), Lifecycle.REGISTERED, clock.instant(), clock.instant());
        if (!catalog.create(dataset)) {
            throw new IllegalStateException("dataset name already exists in workspace");
        }

        Profile profile = Objects.requireNonNull(
                profiler.profile(dataset.id(), dataset.workspaceId(), staged), "profile");
        if (!dataset.id().equals(profile.datasetId()) || !dataset.workspaceId().equals(profile.workspaceId())) {
            throw new IllegalArgumentException("profiler returned a profile for a different dataset or workspace");
        }
        catalog.saveProfile(profile);
        return new ImportedDataset(dataset, profile);
    }

    public record ImportedDataset(Dataset dataset, Profile profile) {
        public ImportedDataset {
            Objects.requireNonNull(dataset, "dataset");
            Objects.requireNonNull(profile, "profile");
        }
    }
}
