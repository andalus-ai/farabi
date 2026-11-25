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
 * Provider-key adapter for Dremio. A host-supplied factory resolves the
 * connection definition and creates the endpoint-specific REST clients/catalog.
 * This keeps credentials and per-source HTTP configuration outside the engine.
 *
 * <p>Dremio SQL parameter binding is not implemented by the current REST
 * executor. The provider refuses parameterized plans rather than dropping values.</p>
 */
public final class DremioSourceProvider implements DataSourceContracts.Provider {
    private static final Capabilities CAPABILITIES = new Capabilities(
            Set.of(Feature.CANCELLATION, Feature.RESULT_PAGING, Feature.SCHEMAS, Feature.VIEWS,
                    Feature.OFFSET_PAGINATION),
            1_000_000,
            1_000);

    public record Source(
            Descriptor descriptor,
            Catalog catalog,
            DremioExecutor executor,
            DremioJobProvider lifecycle) {
        public Source {
            Objects.requireNonNull(descriptor, "descriptor");
            Objects.requireNonNull(catalog, "catalog");
            Objects.requireNonNull(executor, "executor");
            Objects.requireNonNull(lifecycle, "lifecycle");
        }
    }

    @FunctionalInterface
    public interface SourceFactory {
        Source open(ConnectionDefinition definition);
    }

    private final SourceFactory sources;

    public DremioSourceProvider(SourceFactory sources) {
        this.sources = Objects.requireNonNull(sources, "sources");
    }

    @Override
    public Descriptor descriptor(ConnectionDefinition definition) {
        return source(definition).descriptor();
    }

    @Override
    public Capabilities capabilities(ConnectionDefinition definition) {
        source(definition);
        return CAPABILITIES;
    }

    @Override
    public Catalog catalog(ConnectionDefinition definition) {
        return source(definition).catalog();
    }

    @Override
    public QueryPorts.Executor executor(ConnectionDefinition definition) {
        return source(definition).executor();
    }

    @Override
    public QueryPorts.ParameterizedExecutor parameterizedExecutor(ConnectionDefinition definition) {
        source(definition);
        return plan -> {
            Objects.requireNonNull(plan, "plan");
            throw new UnsupportedOperationException(
                    "Dremio provider does not support parameterized query execution");
        };
    }

    @Override
    public QueryLifecycle.Provider lifecycle(ConnectionDefinition definition) {
        return source(definition).lifecycle();
    }

    /** A configured adapter is not proof that the remote endpoint is reachable. */
    @Override
    public SourceHealth health(ConnectionDefinition definition) {
        source(definition);
        return new SourceHealth(Health.UNKNOWN, Instant.now(), null);
    }

    private Source source(ConnectionDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        if (definition.providerKind() != ProviderKind.DREMIO
                || !"dremio".equalsIgnoreCase(definition.providerKey())) {
            throw new IllegalArgumentException("connection definition is not configured for Dremio");
        }
        Source source = Objects.requireNonNull(sources.open(definition),
                "Dremio source factory returned null");
        if (!definition.sourceId().equals(source.descriptor().sourceId())
                || !"dremio".equalsIgnoreCase(source.descriptor().providerId())) {
            throw new IllegalStateException("Dremio source factory returned a descriptor for another source");
        }
        return source;
    }
}
