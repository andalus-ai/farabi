package tech.kayys.farabi.engine;

import java.util.List;
import java.util.Optional;
import tech.kayys.farabi.engine.DataSourceContracts.CatalogEntry;
import tech.kayys.farabi.engine.DataSourceContracts.ColumnMetadata;
import tech.kayys.farabi.engine.DataSourceContracts.ConnectionDefinition;
import tech.kayys.farabi.engine.DataSourceContracts.Descriptor;
import tech.kayys.farabi.engine.DataSourceContracts.Provider;
import tech.kayys.farabi.engine.QueryContracts.ParameterizedPlan;
import tech.kayys.farabi.engine.QueryContracts.Submission;

/** Ports for datasource persistence, discovery, authorization, and execution. */
public final class DataSourcePorts {
    private DataSourcePorts() {}

    public interface Registry {
        Optional<Descriptor> find(String sourceId);
        List<Descriptor> list();
    }

    /** Workspace-scoped persistence; implementations must include workspace in every lookup. */
    public interface ConnectionRegistry {
        boolean register(ConnectionDefinition definition);
        Optional<ConnectionDefinition> find(String sourceId, String workspaceId);
        List<ConnectionDefinition> list(String workspaceId);
        boolean remove(String sourceId, String workspaceId);
    }

    public interface CatalogProvider {
        List<CatalogEntry> listObjects(String sourceId, String catalog, String schemaPattern);
        List<ColumnMetadata> listColumns(String sourceId, String catalog, String schema, String objectName);
    }

    /** Reports source capabilities; capability hints are never authorization decisions. */
    @FunctionalInterface
    public interface ProviderRegistry {
        Optional<Provider> find(String providerKey);
    }

    /** Executes a plan only after the engine has applied authorization and policy validation. */
    @FunctionalInterface
    public interface QueryExecutor {
        Submission execute(ParameterizedPlan plan);
    }

    /** Resolves opaque secret references at connection-open time; callers must wipe returned buffers. */
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
