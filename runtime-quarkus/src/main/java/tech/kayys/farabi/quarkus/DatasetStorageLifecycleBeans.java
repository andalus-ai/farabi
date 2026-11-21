package tech.kayys.farabi.quarkus;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import io.quarkus.arc.DefaultBean;
import io.quarkus.arc.properties.IfBuildProperty;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import tech.kayys.farabi.engine.DatasetStorageLifecyclePorts;
import tech.kayys.farabi.engine.DatasetDeletionService;
import tech.kayys.farabi.engine.DatasetDeletionWorker;
import tech.kayys.farabi.engine.DatasetDeletionRecoveryAuthorizer;
import tech.kayys.farabi.file.LocalDatasetObjectStore;
import tech.kayys.farabi.jdbc.JdbcDatasetDeletionStore;
import tech.kayys.farabi.s3.S3CompatibleDatasetAdapter;

/** CDI wiring for the durable storage deletion outbox and worker. */
@ApplicationScoped
public class DatasetStorageLifecycleBeans {
    @Produces @Singleton
    JdbcDatasetDeletionStore deletionStore(DataSource source) { return new JdbcDatasetDeletionStore(source); }

    @Produces @Singleton @DefaultBean
    DatasetStorageLifecyclePorts.ObjectStore localObjects(
            @ConfigProperty(name="farabi.import.storage.root", defaultValue="/var/lib/farabi/datasets") String root) {
        return new LocalDatasetObjectStore(Path.of(root));
    }

    @Produces @Singleton @DefaultBean
    DatasetStorageLifecyclePorts.Authorizer denyDeletionByDefault() {
        return (workspace, subject, dataset) -> {
            throw new jakarta.ws.rs.ForbiddenException("dataset deletion policy is not configured");
        };
    }

    @Produces @Singleton @DefaultBean
    DatasetDeletionRecoveryAuthorizer denyDeletionRecoveryByDefault() {
        return (subject, workspace) -> {
            throw new jakarta.ws.rs.ForbiddenException("dataset deletion recovery policy is not configured");
        };
    }

    @Produces @Singleton
    DatasetDeletionService deletionService(DatasetStorageLifecyclePorts.Authorizer authorizer,
            JdbcDatasetDeletionStore store,
            @ConfigProperty(name="farabi.import.max-attempts", defaultValue="5") int attempts) {
        return new DatasetDeletionService(authorizer, store, Clock.systemUTC(), attempts);
    }

    @Produces @Singleton
    DatasetDeletionWorker deletionWorker(JdbcDatasetDeletionStore store,
            DatasetStorageLifecyclePorts.ObjectStore objects,
            @ConfigProperty(name="farabi.import.worker.id", defaultValue="farabi-quarkus-worker") String workerId,
            @ConfigProperty(name="farabi.import.lease-duration", defaultValue="PT2M") Duration lease) {
        return new DatasetDeletionWorker(store, objects, Clock.systemUTC(), workerId + "-deletion", lease);
    }
}
