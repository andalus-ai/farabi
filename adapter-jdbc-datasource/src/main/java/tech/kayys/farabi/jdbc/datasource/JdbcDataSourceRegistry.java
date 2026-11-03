package tech.kayys.farabi.jdbc.datasource;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import tech.kayys.farabi.engine.DataSourceContracts.DataSourceDescriptor;
import tech.kayys.farabi.engine.DataSourcePorts.Registry;

/**
 * Immutable registry of application-managed JDBC pools.
 * Pool creation, credentials, TLS and secret rotation belong to runtime wiring,
 * never to the engine or this adapter.
 */
public final class JdbcDataSourceRegistry implements Registry {
    private final Map<String, Entry> entries;

    public JdbcDataSourceRegistry(Map<String, Entry> entries) {
        this.entries = Map.copyOf(Objects.requireNonNull(entries, "entries"));
        this.entries.forEach((id, entry) -> {
            if (!id.equals(entry.descriptor().id())) {
                throw new IllegalArgumentException("registry key must match descriptor id");
            }
        });
    }

    public DataSource requireDataSource(String sourceId) {
        Entry entry = entries.get(sourceId);
        if (entry == null) throw new IllegalArgumentException("unknown data source: " + sourceId);
        return entry.dataSource();
    }

    @Override public Optional<DataSourceDescriptor> find(String sourceId) {
        Entry entry = entries.get(sourceId);
        return entry == null ? Optional.empty() : Optional.of(entry.descriptor());
    }

    @Override public List<DataSourceDescriptor> list() {
        return entries.values().stream().map(Entry::descriptor)
                .sorted(java.util.Comparator.comparing(DataSourceDescriptor::id)).toList();
    }

    public record Entry(DataSourceDescriptor descriptor, DataSource dataSource) {
        public Entry {
            Objects.requireNonNull(descriptor, "descriptor");
            Objects.requireNonNull(dataSource, "dataSource");
            if (descriptor.providerKind() != tech.kayys.farabi.engine.DataSourceContracts.ProviderKind.JDBC) {
                throw new IllegalArgumentException("JDBC registry accepts JDBC descriptors only");
            }
        }
    }
}
