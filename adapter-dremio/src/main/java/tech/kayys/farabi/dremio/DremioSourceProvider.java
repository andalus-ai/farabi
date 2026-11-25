package tech.kayys.farabi.dremio;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import tech.kayys.farabi.engine.DataSourceContracts.Catalog;
import tech.kayys.farabi.engine.DataSourceContracts.CatalogEntry;
import tech.kayys.farabi.engine.DataSourceContracts.Capabilities;
import tech.kayys.farabi.engine.DataSourceContracts.ColumnMetadata;
import tech.kayys.farabi.engine.DataSourceContracts.ConnectionDefinition;
import tech.kayys.farabi.engine.DataSourceContracts.Descriptor;
import tech.kayys.farabi.engine.DataSourceContracts.EntryKind;
import tech.kayys.farabi.engine.DataSourceContracts.Feature;
import tech.kayys.farabi.engine.DataSourceContracts.Health;
import tech.kayys.farabi.engine.DataSourceContracts.ProviderKind;
import tech.kayys.farabi.engine.DataSourceContracts.SourceHealth;
import tech.kayys.farabi.engine.DataSourcePorts.Provider;

/**
 * Dremio adapter implementing the single engine-owned datasource provider SPI.
 * Query execution and asynchronous job lifecycle remain separately composed ports.
 */
public final class DremioSourceProvider implements Provider {
    public static final String PROVIDER_KEY = "dremio";

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
    public String providerKey() {
        return PROVIDER_KEY;
    }

    @Override
    public Capabilities capabilities(ConnectionDefinition definition) {
        source(definition);
        return CAPABILITIES;
    }

    @Override
    public SourceHealth testConnection(ConnectionDefinition definition) {
        try {
            source(definition);
            return new SourceHealth(Health.AVAILABLE, Instant.now(), null);
        } catch (SecurityException denied) {
            throw denied;
        } catch (RuntimeException unavailable) {
            return new SourceHealth(Health.UNAVAILABLE, Instant.now(), "Dremio source check failed");
        }
    }

    @Override
    public List<String> catalogs(ConnectionDefinition definition) {
        return source(definition).catalog().catalogs().stream().map(CatalogEntry::name).toList();
    }

    @Override
    public List<String> schemas(ConnectionDefinition definition, String catalog) {
        return source(definition).catalog().schemas(catalog).stream().map(CatalogEntry::name).toList();
    }

    @Override
    public List<CatalogEntry> datasets(ConnectionDefinition definition, String catalog, String schema) {
        return List.copyOf(source(definition).catalog().relations(catalog, schema));
    }

    @Override
    public List<ColumnMetadata> columns(
            ConnectionDefinition definition, String catalog, String schema, String dataset) {
        var relation = source(definition).catalog().describe(dataset);
        if (catalog != null && !catalog.isBlank() && !catalog.equals(relation.catalog())) {
            throw new IllegalArgumentException("dataset does not belong to the requested catalog");
        }
        if (schema != null && !schema.isBlank() && !schema.equals(relation.schema())) {
            throw new IllegalArgumentException("dataset does not belong to the requested schema");
        }
        return List.copyOf(relation.columns());
    }

    /** Query execution remains separately available to the runtime composition root. */
    public DremioExecutor executor(ConnectionDefinition definition) {
        return source(definition).executor();
    }

    /** Dremio currently rejects parameterized plans rather than silently dropping values. */
    public java.util.function.Function<tech.kayys.farabi.engine.QueryContracts.ParameterizedPlan,
            tech.kayys.farabi.engine.QueryContracts.Submission> parameterizedExecutor(
                    ConnectionDefinition definition) {
        source(definition);
        return plan -> {
            Objects.requireNonNull(plan, "plan");
            throw new UnsupportedOperationException(
                    "Dremio provider does not support parameterized query execution");
        };
    }

    public DremioJobProvider lifecycle(ConnectionDefinition definition) {
        return source(definition).lifecycle();
    }

    private Source source(ConnectionDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        if (definition.providerKind() != ProviderKind.DREMIO
                || !PROVIDER_KEY.equalsIgnoreCase(definition.providerKey())) {
            throw new IllegalArgumentException("connection definition is not configured for Dremio");
        }
        Source source = Objects.requireNonNull(sources.open(definition),
                "Dremio source factory returned null");
        if (!definition.sourceId().equals(source.descriptor().sourceId())
                || !PROVIDER_KEY.equalsIgnoreCase(source.descriptor().providerId())) {
            throw new IllegalStateException("Dremio source factory returned a descriptor for another source");
        }
        return source;
    }
}
