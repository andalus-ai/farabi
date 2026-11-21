package tech.kayys.farabi.dremio;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import tech.kayys.farabi.engine.DataSourceContracts;
import tech.kayys.farabi.engine.DataSourceContracts.Catalog;
import tech.kayys.farabi.engine.DataSourceContracts.Capabilities;
import tech.kayys.farabi.engine.DataSourceContracts.ConnectionDefinition;
import tech.kayys.farabi.engine.DataSourceContracts.Descriptor;
import tech.kayys.farabi.engine.DataSourceContracts.Feature;
import tech.kayys.farabi.engine.DataSourceContracts.Health;
import tech.kayys.farabi.engine.DataSourceContracts.ProviderKind;
import tech.kayys.farabi.engine.DataSourceContracts.SourceHealth;
import tech.kayys.farabi.engine.QueryPorts;
import tech.kayys.farabi.engine.QueryLifecycle;

/**
 * Bridges the Dremio REST adapters into Farabi's provider-neutral datasource
 * contract. Catalog discovery is injected so the adapter never leaks Dremio
 * REST payloads into the engine API.
 *
 * <p>Dremio's SQL REST endpoint currently has no parameter-binding support in
 * this adapter. The provider therefore does not advertise that capability and
 * refuses parameterized plans rather than silently dropping their values.</p>
 */
public final class DremioSourceProvider implements DataSourceContracts.Provider {
    private static final Capabilities CAPABILITIES = new Capabilities(
            Set.of(Feature.CANCELLATION, Feature.RESULT_PAGING, Feature.SCHEMAS, Feature.VIEWS,
                    Feature.OFFSET_PAGINATION),
            1_000_000,
            1_000);

    private final Descriptor descriptor;
    private final Catalog catalog;
    private final DremioExecutor executor;
    private final DremioJobProvider lifecycle;

    public DremioSourceProvider(
            Descriptor descriptor,
            Catalog catalog,
            DremioExecutor executor,
            DremioJobProvider lifecycle) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        if (!"dremio".equalsIgnoreCase(descriptor.providerId())) {
            throw new IllegalArgumentException("providerId must be dremio");
        }
    }

    public Descriptor descriptor() {
        return descriptor;
    }

    public Capabilities capabilities() {
        return CAPABILITIES;
    }

    public Catalog catalog() {
        return catalog;
    }

    public QueryPorts.Executor executor() {
        return executor;
    }

    public QueryPorts.ParameterizedExecutor parameterizedExecutor() {
        return plan -> {
            Objects.requireNonNull(plan, "plan");
            throw new UnsupportedOperationException(
                    "Dremio provider does not support parameterized query execution");
        };
    }

    public QueryLifecycle.Provider lifecycle() {
        return lifecycle;
    }

    /** A configured adapter is not proof that the remote endpoint is reachable. */
    public Health health() {
        return Health.UNKNOWN;
    }

    @Override
    public Descriptor descriptor(ConnectionDefinition definition) {
        requireDefinition(definition);
        return descriptor;
    }

    @Override
    public Capabilities capabilities(ConnectionDefinition definition) {
        requireDefinition(definition);
        return capabilities();
    }

    @Override
    public Catalog catalog(ConnectionDefinition definition) {
        requireDefinition(definition);
        return catalog();
    }

    @Override
    public QueryPorts.Executor executor(ConnectionDefinition definition) {
        requireDefinition(definition);
        return executor();
    }

    @Override
    public QueryPorts.ParameterizedExecutor parameterizedExecutor(ConnectionDefinition definition) {
        requireDefinition(definition);
        return parameterizedExecutor();
    }

    @Override
    public QueryLifecycle.Provider lifecycle(ConnectionDefinition definition) {
        requireDefinition(definition);
        return lifecycle();
    }

    @Override
    public SourceHealth health(ConnectionDefinition definition) {
        requireDefinition(definition);
        return new SourceHealth(health(), Instant.now(), null);
    }

    private void requireDefinition(ConnectionDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        if (definition.providerKind() != ProviderKind.DREMIO
                || !"dremio".equalsIgnoreCase(definition.providerKey())
                || !descriptor.sourceId().equals(definition.sourceId())) {
            throw new IllegalArgumentException("connection definition does not match this Dremio provider");
        }
    }
}
