package tech.kayys.farabi.quarkus;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import tech.kayys.farabi.engine.DatasetImportJobPorts.SourceResolver;
import tech.kayys.farabi.engine.DatasetImportWorker;
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
