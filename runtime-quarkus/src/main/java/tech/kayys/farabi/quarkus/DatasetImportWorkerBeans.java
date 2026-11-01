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
import tech.kayys.farabi.jdbc.JdbcWorkspaceStorageStore;
import tech.kayys.farabi.jdbc.JdbcDatasetStorageReconciliationStore;
import tech.kayys.farabi.engine.DatasetStorageReconciliationService;
import tech.kayys.farabi.engine.DatasetStorageInventoryService;
import tech.kayys.farabi.engine.DatasetStorageInventoryWorker;
import tech.kayys.farabi.engine.DatasetStorageInventoryJobPorts;
import tech.kayys.farabi.engine.DatasetStorageOperatorAuthorizer;
import tech.kayys.farabi.engine.DatasetStorageInventoryPorts.Inventory;
import tech.kayys.farabi.jdbc.JdbcDatasetStorageReferenceStore;
import tech.kayys.farabi.jdbc.JdbcDatasetStorageInventoryReportStore;
import tech.kayys.farabi.jdbc.JdbcDatasetStorageInventoryJobStore;
import tech.kayys.farabi.jdbc.JdbcDatasetStorageInventoryAuditLog;
import tech.kayys.farabi.jdbc.JdbcDatasetStorageCleanupPreviewStore;
import tech.kayys.farabi.engine.DatasetStorageCleanupPreviewService;
import tech.kayys.farabi.engine.DatasetStorageInventoryAuditPorts.AuditLog;
import tech.kayys.farabi.engine.DatasetStorageLifecyclePorts.ObjectStore;

/** CDI wiring for dataset imports and storage inventory workers. */
@ApplicationScoped
public class DatasetImportWorkerBeans {
    @Produces @Singleton @DefaultBean
    DatasetStorageOperatorAuthorizer denyStorageOperationsByDefault() {
        return (subjectId, workspaceId) -> { throw new jakarta.ws.rs.ForbiddenException("storage operator authorization is not configured"); };
    }
    @Produces @Singleton JdbcDatasetCatalog datasetCatalog(DataSource source) { return new JdbcDatasetCatalog(source); }
    @Produces @Singleton JdbcDatasetImportJobStore datasetImportJobs(DataSource source) { return new JdbcDatasetImportJobStore(source); }
    @Produces @Singleton JdbcDatasetStorageReconciliationStore storageReconciliationStore(DataSource source) { return new JdbcDatasetStorageReconciliationStore(source); }
    @Produces @Singleton DatasetStorageReconciliationService storageReconciliationService(JdbcDatasetStorageReconciliationStore store) {
        return new DatasetStorageReconciliationService(store, Clock.systemUTC());
    }
    @Produces @Singleton JdbcDatasetStorageReferenceStore datasetStorageReferences(DataSource source) { return new JdbcDatasetStorageReferenceStore(source); }
    @Produces @Singleton JdbcDatasetStorageInventoryReportStore storageInventoryReportStore(DataSource source) {
        return new JdbcDatasetStorageInventoryReportStore(source);
    }
    @Produces @Singleton JdbcDatasetStorageInventoryJobStore storageInventoryJobStore(DataSource source) {
        return new JdbcDatasetStorageInventoryJobStore(source);
    }
    @Produces @Singleton JdbcDatasetStorageInventoryAuditLog storageInventoryAuditLog(DataSource source) {
        return new JdbcDatasetStorageInventoryAuditLog(source);
    }
    @Produces @Singleton JdbcDatasetStorageCleanupPreviewStore storageCleanupPreviewStore(DataSource source) {
        return new JdbcDatasetStorageCleanupPreviewStore(source);
    }
    @Produces @Singleton DatasetStorageCleanupPreviewService storageCleanupPreviewService(
            JdbcDatasetStorageInventoryReportStore reports, JdbcDatasetStorageReferenceStore references,
            Inventory inventory, JdbcDatasetStorageCleanupPreviewStore plans,
            @ConfigProperty(name="farabi.storage.cleanup-preview.lifetime", defaultValue="PT15M") Duration lifetime) {
        return new DatasetStorageCleanupPreviewService(reports, references, inventory, plans, Clock.systemUTC(), lifetime);
    }
    @Produces @Singleton DatasetStorageInventoryService datasetStorageInventoryService(
            JdbcDatasetStorageReferenceStore references, Inventory inventory, JdbcDatasetStorageInventoryReportStore reports,
            @ConfigProperty(name="farabi.import.storage.orphan-grace-period", defaultValue="PT24H") Duration gracePeriod) {
        return new DatasetStorageInventoryService(references, inventory, Clock.systemUTC(), gracePeriod, reports);
    }
    @Produces @Singleton DatasetStorageInventoryWorker datasetStorageInventoryWorker(
            JdbcDatasetStorageInventoryJobStore jobs, DatasetStorageInventoryService inventory, AuditLog audit,
            @ConfigProperty(name="farabi.storage.inventory.worker-id", defaultValue="farabi-storage-inventory-worker") String workerId,
            @ConfigProperty(name="farabi.storage.inventory.lease-duration", defaultValue="PT2M") Duration leaseDuration) {
        return new DatasetStorageInventoryWorker(workerId, jobs, inventory, Clock.systemUTC(), leaseDuration, audit);
    }
    @Produces @Singleton JdbcWorkspaceStorageStore workspaceStorage(DataSource source,
            @ConfigProperty(name="farabi.import.storage.default-quota-bytes", defaultValue="10737418240") long quotaBytes) {
        return new JdbcWorkspaceStorageStore(source, quotaBytes);
    }
    @Produces @Singleton @DefaultBean LocalFileDatasetAdapter localDatasetFiles(
            @ConfigProperty(name="farabi.import.storage.root", defaultValue="/var/lib/farabi/datasets") String root) {
        return new LocalFileDatasetAdapter(Path.of(root),
                request -> { throw new IllegalStateException("HTTP upload content provider is not configured"); },
                new LocalFileDatasetAdapter.Limits(512L*1024*1024, 5_000_000, 500, 100_000), Clock.systemUTC());
    }
    @Produces @Singleton @DefaultBean DatasetPorts.Authorizer denyDatasetRegistrationByDefault() {
        return (workspaceId, subjectId, sourceId) -> { throw new jakarta.ws.rs.ForbiddenException("dataset source registration is not configured"); };
    }
    @Produces @Singleton DatasetImportSubmissionService datasetImportSubmissionService(
            DatasetPorts.Authorizer authorizer, JdbcDatasetCatalog catalog, UploadSourceIntake intake, JdbcDatasetImportJobStore jobs,
            @ConfigProperty(name="farabi.import.max-attempts", defaultValue="5") int maxAttempts,
            JdbcWorkspaceStorageStore storage, ObjectStore objects) {
        return new DatasetImportSubmissionService(authorizer, catalog, intake, jobs, Clock.systemUTC(), maxAttempts, storage, jobs, objects);
    }
    @Produces @Singleton @IfBuildProperty(name="farabi.import.storage.provider", stringValue="s3")
    S3Client datasetS3Client(@ConfigProperty(name="farabi.import.s3.region", defaultValue="ap-southeast-1") String region,
            @ConfigProperty(name="farabi.import.s3.endpoint", defaultValue="") String endpoint) {
        var builder=S3Client.builder().region(Region.of(region)).serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
        if(endpoint!=null&&!endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint)); return builder.build();
    }
    @Produces @Singleton @IfBuildProperty(name="farabi.import.storage.provider", stringValue="s3")
    S3CompatibleDatasetAdapter s3DatasetAdapter(S3Client client, @ConfigProperty(name="farabi.import.s3.bucket") String bucket,
            @ConfigProperty(name="farabi.import.storage.temp-root", defaultValue="/var/tmp/farabi-datasets") String tempRoot,
            @ConfigProperty(name="farabi.import.max-bytes", defaultValue="536870912") long maxBytes) {
        return new S3CompatibleDatasetAdapter(client,bucket,Path.of(tempRoot),maxBytes,
                new LocalFileDatasetAdapter.Limits(maxBytes,5_000_000,500,100_000),Clock.systemUTC());
    }
    @Produces @Singleton SourceResolver datasetSourceResolver() { return dataset -> new StagedSource(dataset.format(),dataset.locationRef()); }
    @Produces @Singleton DatasetImportWorker datasetImportWorker(JdbcDatasetImportJobStore jobs, JdbcDatasetCatalog catalog,
            SourceResolver resolver, Profiler profiler,
            @ConfigProperty(name="farabi.import.worker.id", defaultValue="farabi-quarkus-worker") String workerId,
            @ConfigProperty(name="farabi.import.lease-duration", defaultValue="PT2M") Duration leaseDuration) {
        return new DatasetImportWorker(workerId,jobs,catalog,resolver,profiler,Clock.systemUTC(),leaseDuration);
    }
}
