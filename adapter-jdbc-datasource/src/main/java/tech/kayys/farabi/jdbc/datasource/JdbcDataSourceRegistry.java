package tech.kayys.farabi.jdbc.datasource;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import tech.kayys.farabi.engine.DataSourceContracts.Descriptor;
import tech.kayys.farabi.engine.DataSourcePorts.Registry;

/** Immutable registry of application-managed JDBC pools. */
public final class JdbcDataSourceRegistry implements Registry {
    private final Map<String, Entry> entries;

    public JdbcDataSourceRegistry(Map<String, Entry> entries) {
        this.entries = Map.copyOf(Objects.requireNonNull(entries, "entries"));
        this.entries.forEach((id, entry) -> {
            if (!id.equals(entry.descriptor().sourceId()))
                throw new IllegalArgumentException("registry key must match descriptor source id");
        });
    }

    public DataSource requireDataSource(String sourceId) {
        Entry entry = entries.get(sourceId);
        if (entry == null) throw new IllegalArgumentException("unknown data source: " + sourceId);
        return entry.dataSource();
    }

    @Override public Optional<Descriptor> find(String sourceId) {
        Entry entry = entries.get(sourceId);
        return entry == null ? Optional.empty() : Optional.of(entry.descriptor());
    }

    @Override public List<Descriptor> list() {
        return entries.values().stream().map(Entry::descriptor)
                .sorted(java.util.Comparator.comparing(Descriptor::sourceId)).toList();
    }

    public record Entry(Descriptor descriptor, DataSource dataSource) {
        public Entry {
            Objects.requireNonNull(descriptor, "descriptor");
            Objects.requireNonNull(dataSource, "dataSource");
            if (!"jdbc".equalsIgnoreCase(descriptor.providerId()))
                throw new IllegalArgumentException("JDBC registry accepts JDBC descriptors only");
        }
    }
}
