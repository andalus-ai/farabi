package tech.kayys.farabi.engine;

import java.util.List;
import java.util.Optional;
import tech.kayys.farabi.engine.DataSourceContracts.*;

/** Outbound ports for connection management and source-provider adapters. */
public final class DataSourcePorts {
    private DataSourcePorts() {}

    public interface ConnectionRegistry {
        boolean register(ConnectionDefinition definition);
        Optional<ConnectionDefinition> find(String sourceId, String workspaceId);
        List<ConnectionDefinition> list(String workspaceId);
        boolean remove(String sourceId, String workspaceId);
    }

    /** Adapter factory selected by providerKey; implementations must not bypass engine authorization. */
    public interface Provider {
        String providerKey();
        CapabilitySet capabilities(ConnectionDefinition definition);
        SourceHealth testConnection(ConnectionDefinition definition);
        List<String> catalogs(ConnectionDefinition definition);
        List<String> schemas(ConnectionDefinition definition, String catalog);
        List<CatalogEntry> datasets(ConnectionDefinition definition, String catalog, String schema);
        List<ColumnMetadata> columns(ConnectionDefinition definition, String catalog, String schema, String dataset);
    }

    @FunctionalInterface
    public interface ProviderRegistry {
        Optional<Provider> find(String providerKey);
    }

    /** Resolves secret references at the adapter boundary; secret material must never enter domain objects. */
    @FunctionalInterface
    public interface SecretResolver {
        char[] resolve(String secretReference);
    }

    @FunctionalInterface
    public interface ConnectionAuthorizer {
        void authorize(String workspaceId, String subjectId, String sourceId, Action action);
    }

    public enum Action { REGISTER, VIEW, TEST, DISCOVER, QUERY, DELETE }
}
