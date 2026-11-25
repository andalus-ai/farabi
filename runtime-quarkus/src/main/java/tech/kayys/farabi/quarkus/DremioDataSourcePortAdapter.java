package tech.kayys.farabi.quarkus;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import tech.kayys.farabi.dremio.DremioSourceProvider;
import tech.kayys.farabi.engine.DataSourceContracts;
import tech.kayys.farabi.engine.DataSourceContracts.Catalog;
import tech.kayys.farabi.engine.DataSourceContracts.CatalogEntry;
import tech.kayys.farabi.engine.DataSourceContracts.CapabilitySet;
import tech.kayys.farabi.engine.DataSourceContracts.ConnectionDefinition;
import tech.kayys.farabi.engine.DataSourceContracts.EntryKind;
import tech.kayys.farabi.engine.DataSourceContracts.Health;
import tech.kayys.farabi.engine.DataSourceContracts.SourceHealth;
import tech.kayys.farabi.engine.DataSourcePorts.Provider;

/**
 * Bridges the richer Dremio provider contract into the engine's governed
 * datasource application port. Dremio IDs remain opaque at this boundary;
 * callers may use either an ID or an unambiguous display name for navigation.
 */
final class DremioDataSourcePortAdapter implements Provider {
    private final DremioSourceProvider delegate;

    DremioDataSourcePortAdapter(DremioSourceProvider delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public String providerKey() {
        return "dremio";
    }

    @Override
    public CapabilitySet capabilities(ConnectionDefinition definition) {
        delegate.capabilities(definition);
        return new CapabilitySet(true, true, true, true, true, true,
                false, false,
                List.of("BOOLEAN", "INTEGER", "DECIMAL", "STRING", "DATE",
                        "TIME", "TIMESTAMP", "BINARY", "JSON", "UNKNOWN"),
                "dremio-sql");
    }

    @Override
    public SourceHealth testConnection(ConnectionDefinition definition) {
        Instant checkedAt = Instant.now();
        try {
            delegate.catalog(definition).catalogs();
            return new SourceHealth(Health.AVAILABLE, checkedAt, null);
        } catch (SecurityException denied) {
            return new SourceHealth(Health.UNAVAILABLE, checkedAt, "Dremio rejected the configured credentials");
        } catch (RuntimeException unavailable) {
            return new SourceHealth(Health.UNAVAILABLE, checkedAt, "Dremio catalog probe failed");
        }
    }

    @Override
    public List<String> catalogs(ConnectionDefinition definition) {
        return delegate.catalog(definition).catalogs().stream().map(CatalogEntry::name).toList();
    }

    @Override
    public List<String> schemas(ConnectionDefinition definition, String catalog) {
        Catalog source = delegate.catalog(definition);
        CatalogEntry catalogEntry = resolve(source.catalogs(), catalog, EntryKind.CATALOG, "catalog");
        return source.schemas(catalogEntry.id()).stream().map(CatalogEntry::name).toList();
    }

    @Override
    public List<CatalogEntry> datasets(ConnectionDefinition definition, String catalog, String schema) {
        Catalog source = delegate.catalog(definition);
        CatalogEntry catalogEntry = resolve(source.catalogs(), catalog, EntryKind.CATALOG, "catalog");
        String schemaId = null;
        if (schema != null && !schema.isBlank()) {
            CatalogEntry schemaEntry = resolve(source.schemas(catalogEntry.id()), schema, EntryKind.SCHEMA, "schema");
            schemaId = schemaEntry.id();
        }
        return source.relations(catalogEntry.id(), schemaId);
    }

    @Override
    public List<DataSourceContracts.ColumnMetadata> columns(
            ConnectionDefinition definition, String catalog, String schema, String dataset) {
        Catalog source = delegate.catalog(definition);
        CatalogEntry catalogEntry = resolve(source.catalogs(), catalog, EntryKind.CATALOG, "catalog");
        String schemaId = null;
        if (schema != null && !schema.isBlank()) {
            schemaId = resolve(source.schemas(catalogEntry.id()), schema, EntryKind.SCHEMA, "schema").id();
        }
        List<CatalogEntry> relations = source.relations(catalogEntry.id(), schemaId);
        CatalogEntry relation = resolve(relations, dataset, null, "dataset");
        return source.describe(relation.id()).columns();
    }

    private static CatalogEntry resolve(
            List<CatalogEntry> entries, String value, EntryKind expected, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
        List<CatalogEntry> matches = entries.stream()
                .filter(entry -> entry.id().equals(value) || entry.name().equals(value))
                .filter(entry -> expected == null || entry.kind() == expected)
                .toList();
        if (matches.isEmpty()) throw new IllegalArgumentException("Dremio " + label + " was not found");
        if (matches.size() > 1) throw new IllegalArgumentException("Dremio " + label + " name is ambiguous; use its ID");
        return matches.getFirst();
    }
}
