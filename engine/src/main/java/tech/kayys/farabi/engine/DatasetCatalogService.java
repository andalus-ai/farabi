package tech.kayys.farabi.engine;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import tech.kayys.farabi.engine.DatasetContracts.Dataset;
import tech.kayys.farabi.engine.DatasetContracts.Format;
import tech.kayys.farabi.engine.DatasetContracts.Lifecycle;

/** Application service that registers datasets only after server-side authorization. */
public final class DatasetCatalogService {
    public record RegisterRequest(
            String workspaceId,
            String subjectId,
            String name,
            String sourceId,
            Format format,
            String locationRef) {
        public RegisterRequest {
            QueryContracts.required(workspaceId, "workspaceId");
            QueryContracts.required(subjectId, "subjectId");
            QueryContracts.required(name, "name");
            QueryContracts.required(sourceId, "sourceId");
            Objects.requireNonNull(format, "format");
            QueryContracts.required(locationRef, "locationRef");
        }
    }

    private final DatasetPorts.Authorizer authorizer;
    private final DatasetPorts.Catalog catalog;
    private final Clock clock;

    public DatasetCatalogService(
            DatasetPorts.Authorizer authorizer, DatasetPorts.Catalog catalog, Clock clock) {
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Dataset register(RegisterRequest request) {
        Objects.requireNonNull(request, "request");
        authorizer.authorizeRegistration(request.workspaceId(), request.subjectId(), request.sourceId());
        Instant now = clock.instant();
        Dataset dataset = new Dataset(
                UUID.randomUUID(), request.workspaceId(), request.name(), request.sourceId(),
                request.format(), request.locationRef(), Lifecycle.REGISTERED, now, now);
        if (!catalog.create(dataset)) {
            throw new IllegalStateException("dataset name already exists in workspace");
        }
        return dataset;
    }
}
