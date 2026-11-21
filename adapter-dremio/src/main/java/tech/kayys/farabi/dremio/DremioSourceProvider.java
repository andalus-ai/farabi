package tech.kayys.farabi.dremio;

import java.util.Objects;
import java.util.Set;
import tech.kayys.farabi.engine.DataSourceContracts;
import tech.kayys.farabi.engine.DataSourceContracts.Catalog;
import tech.kayys.farabi.engine.DataSourceContracts.Capabilities;
import tech.kayys.farabi.engine.DataSourceContracts.Descriptor;
import tech.kayys.farabi.engine.DataSourceContracts.Feature;
import tech.kayys.farabi.engine.DataSourceContracts.Health;
import tech.kayys.farabi.engine.QueryContracts.ParameterizedPlan;
import tech.kayys.farabi.engine.QueryContracts.Submission;
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

    @Override
    public Descriptor descriptor() {
        return descriptor;
    }

    @Override
    public Capabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public Catalog catalog() {
        return catalog;
    }

    @Override
    public QueryPorts.Executor executor() {
        return executor;
    }

    @Override
    public QueryPorts.ParameterizedExecutor parameterizedExecutor() {
        return plan -> {
            Objects.requireNonNull(plan, "plan");
            throw new UnsupportedOperationException(
                    "Dremio provider does not support parameterized query execution");
        };
    }

    @Override
    public QueryLifecycle.Provider lifecycle() {
        return lifecycle;
    }

    /** A configured adapter is not proof that the remote endpoint is reachable. */
    @Override
    public Health health() {
        return Health.UNKNOWN;
    }
}
