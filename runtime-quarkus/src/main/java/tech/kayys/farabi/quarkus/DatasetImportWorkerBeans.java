package tech.kayys.farabi.quarkus;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import io.quarkus.arc.DefaultBean;
import jakarta.inject.Singleton;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import tech.kayys.farabi.engine.DatasetImportJobPorts.SourceResolver;
import tech.kayys.farabi.engine.DatasetImportWorker;
import tech.kayys.farabi.engine.DatasetImportSubmissionService;
import tech.kayys.farabi.engine.DatasetPorts;
import tech.kayys.farabi.engine.DatasetImportPorts.StagedSource;
import tech.kayys.farabi.jdbc.JdbcDatasetCatalog;
import tech.kayys.farabi.jdbc.JdbcDatasetImportJobStore;
import tech.kayys.farabi.file.LocalFileDatasetAdapter;

/** CDI wiring for the durable import worker. Upload intake is supplied by the API edge. */
@ApplicationScoped
public class DatasetImportWorkerBeans {
    @Produces @Singleton
    JdbcDatasetCatalog datasetCatalog(DataSource source) {
        return new JdbcDatasetCatalog(source);
    }

    @Produces @Singleton
    JdbcDatasetImportJobStore datasetImportJobs(DataSource source) {
        return new JdbcDatasetImportJobStore(source);
    }

    @Produces @Singleton
    LocalFileDatasetAdapter localDatasetFiles(
            @ConfigProperty(name="farabi.import.storage.root", defaultValue="/var/lib/farabi/datasets") String root) {
        return new LocalFileDatasetAdapter(Path.of(root),
                request -> { throw new IllegalStateException("HTTP upload content provider is not configured"); },
                new LocalFileDatasetAdapter.Limits(512L*1024*1024, 5_000_000, 500, 100_000),
                Clock.systemUTC());
    }

    /** Safe default: deployments must provide an explicit source/workspace authorization policy. */
    @Produces @Singleton @DefaultBean
    DatasetPorts.Authorizer denyDatasetRegistrationByDefault() {
        return (workspaceId, subjectId, sourceId) -> {
            throw new jakarta.ws.rs.ForbiddenException("dataset source registration is not configured");
        };
    }

    @Produces @Singleton
    DatasetImportSubmissionService datasetImportSubmissionService(
            DatasetPorts.Authorizer authorizer, JdbcDatasetCatalog catalog,
            LocalFileDatasetAdapter files, JdbcDatasetImportJobStore jobs,
            @ConfigProperty(name="farabi.import.max-attempts", defaultValue="5") int maxAttempts) {
        return new DatasetImportSubmissionService(authorizer, catalog, files, jobs, Clock.systemUTC(), maxAttempts);
    }

    @Produces @Singleton
    SourceResolver datasetSourceResolver() {
        return dataset -> new StagedSource(dataset.format(), dataset.locationRef());
    }

    @Produces @Singleton
    DatasetImportWorker datasetImportWorker(JdbcDatasetImportJobStore jobs, JdbcDatasetCatalog catalog,
            SourceResolver resolver, LocalFileDatasetAdapter files,
            @ConfigProperty(name="farabi.import.worker.id", defaultValue="farabi-quarkus-worker") String workerId,
            @ConfigProperty(name="farabi.import.lease-duration", defaultValue="PT2M") Duration leaseDuration) {
        return new DatasetImportWorker(workerId, jobs, catalog, resolver, files, Clock.systemUTC(), leaseDuration);
    }
}
