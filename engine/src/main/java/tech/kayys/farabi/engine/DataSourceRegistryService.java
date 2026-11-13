package tech.kayys.farabi.engine;

import java.util.List;
import java.util.Objects;
import tech.kayys.farabi.engine.DataSourceContracts.ConnectionDefinition;
import tech.kayys.farabi.engine.DataSourcePorts.*;

/** Governed application boundary for registering and discovering workspace data sources. */
public final class DataSourceRegistryService {
    private final ConnectionRegistry connections;
    private final ProviderRegistry providers;
    private final ConnectionAuthorizer authorizer;

    public DataSourceRegistryService(ConnectionRegistry connections, ProviderRegistry providers,
                                     ConnectionAuthorizer authorizer) {
        this.connections = Objects.requireNonNull(connections);
        this.providers = Objects.requireNonNull(providers);
        this.authorizer = Objects.requireNonNull(authorizer);
    }

    public void register(ConnectionDefinition definition, String subjectId) {
        Objects.requireNonNull(definition);
        QueryContracts.required(subjectId, "subjectId");
        authorizer.authorize(definition.workspaceId(), subjectId, definition.sourceId(), Action.REGISTER);
        if (providers.find(definition.providerKey()).isEmpty()) {
            throw new IllegalArgumentException("unsupported data source provider: " + definition.providerKey());
        }
        if (!connections.register(definition)) {
            throw new IllegalStateException("data source already exists in workspace");
        }
    }

    public SourceHealth testConnection(String sourceId, String workspaceId, String subjectId) {
        ConnectionDefinition definition = authorized(sourceId, workspaceId, subjectId, Action.TEST);
        Provider provider = provider(definition);
        return provider.testConnection(definition);
    }

    public List<String> catalogs(String sourceId, String workspaceId, String subjectId) {
        ConnectionDefinition definition = authorized(sourceId, workspaceId, subjectId, Action.DISCOVER);
        return List.copyOf(provider(definition).catalogs(definition));
    }

    public List<String> schemas(String sourceId, String workspaceId, String catalog, String subjectId) {
        ConnectionDefinition definition = authorized(sourceId, workspaceId, subjectId, Action.DISCOVER);
        return List.copyOf(provider(definition).schemas(definition, catalog));
    }

    public List<CatalogEntry> datasets(String sourceId, String workspaceId, String catalog,
                                       String schema, String subjectId) {
        ConnectionDefinition definition = authorized(sourceId, workspaceId, subjectId, Action.DISCOVER);
        return List.copyOf(provider(definition).datasets(definition, catalog, schema));
    }

    public List<ColumnMetadata> columns(String sourceId, String workspaceId, String catalog,
                                        String schema, String dataset, String subjectId) {
        ConnectionDefinition definition = authorized(sourceId, workspaceId, subjectId, Action.DISCOVER);
        return List.copyOf(provider(definition).columns(definition, catalog, schema, dataset));
    }

    private ConnectionDefinition authorized(String sourceId, String workspaceId, String subjectId, Action action) {
        QueryContracts.required(sourceId, "sourceId");
        QueryContracts.required(workspaceId, "workspaceId");
        QueryContracts.required(subjectId, "subjectId");
        authorizer.authorize(workspaceId, subjectId, sourceId, action);
        return connections.find(sourceId, workspaceId)
                .orElseThrow(() -> new IllegalArgumentException("data source not found"));
    }

    private Provider provider(ConnectionDefinition definition) {
        return providers.find(definition.providerKey())
                .orElseThrow(() -> new IllegalStateException("configured provider is unavailable"));
    }
}
