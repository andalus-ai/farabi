package tech.kayys.farabi.quarkus;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import io.quarkus.arc.DefaultBean;
import io.quarkus.arc.properties.IfBuildProperty;
import java.net.URI;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import tech.kayys.farabi.engine.DatasetImportPorts.UploadSourceIntake;
import tech.kayys.farabi.engine.DatasetImportPorts.Profiler;
import tech.kayys.farabi.s3.S3CompatibleDatasetAdapter;
import tech.kayys.farabi.file.LocalFileDatasetAdapter;
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

    @Produces @Singleton @DefaultBean
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
            UploadSourceIntake intake, JdbcDatasetImportJobStore jobs,
            @ConfigProperty(name="farabi.import.max-attempts", defaultValue="5") int maxAttempts) {
        return new DatasetImportSubmissionService(authorizer, catalog, intake, jobs, Clock.systemUTC(), maxAttempts);
    }

    @Produces @Singleton @IfBuildProperty(name="farabi.import.storage.provider", stringValue="s3")
    S3Client datasetS3Client(
            @ConfigProperty(name="farabi.import.s3.region", defaultValue="ap-southeast-1") String region,
            @ConfigProperty(name="farabi.import.s3.endpoint", defaultValue="") String endpoint) {
        var builder = S3Client.builder().region(Region.of(region))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
        if (endpoint != null && !endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
        return builder.build();
    }

    @Produces @Singleton @IfBuildProperty(name="farabi.import.storage.provider", stringValue="s3")
    S3CompatibleDatasetAdapter s3DatasetAdapter(
            S3Client client,
            @ConfigProperty(name="farabi.import.s3.bucket") String bucket,
            @ConfigProperty(name="farabi.import.storage.temp-root", defaultValue="/var/tmp/farabi-datasets") String tempRoot,
            @ConfigProperty(name="farabi.import.max-bytes", defaultValue="536870912") long maxBytes) {
        return new S3CompatibleDatasetAdapter(client, bucket, Path.of(tempRoot), maxBytes,
                new LocalFileDatasetAdapter.Limits(maxBytes, 5_000_000, 500, 100_000), Clock.systemUTC());
    }

    @Produces @Singleton
    SourceResolver datasetSourceResolver() {
        return dataset -> new StagedSource(dataset.format(), dataset.locationRef());
    }

    @Produces @Singleton
    DatasetImportWorker datasetImportWorker(JdbcDatasetImportJobStore jobs, JdbcDatasetCatalog catalog,
            SourceResolver resolver, Profiler profiler,
            @ConfigProperty(name="farabi.import.worker.id", defaultValue="farabi-quarkus-worker") String workerId,
            @ConfigProperty(name="farabi.import.lease-duration", defaultValue="PT2M") Duration leaseDuration) {
        return new DatasetImportWorker(workerId, jobs, catalog, resolver, profiler, Clock.systemUTC(), leaseDuration);
    }
}
