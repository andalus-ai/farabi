package tech.kayys.farabi.engine;

import java.util.List;
import java.util.Optional;
import tech.kayys.farabi.engine.DataSourceContracts.*;

/** Replaceable ports for source registration, catalog discovery and bounded query execution. */
public final class DataSourcePorts {
    private DataSourcePorts() {}

    public interface Registry {
        Optional<DataSourceDescriptor> find(String sourceId);
        List<DataSourceDescriptor> list();
    }

    public interface CatalogProvider {
        List<CatalogObject> listObjects(String sourceId, String catalog, String schemaPattern);
        List<ColumnMetadata> listColumns(String sourceId, String catalog, String schema, String objectName);
    }

    /** Reports capabilities of the configured source; values are hints, not authorization. */
    @FunctionalInterface
    public interface CapabilitiesProvider {
        DataSourceCapabilities capabilities(String sourceId);
    }

    /** Executes only plans that have already passed Farabi authorization and policy validation. */
    @FunctionalInterface
    public interface QueryExecutor {
        QueryResult execute(QueryRequest request);
    }
}
