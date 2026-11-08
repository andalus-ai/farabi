package tech.kayys.farabi.engine;

import java.util.List;
import java.util.Optional;
import tech.kayys.farabi.engine.DataSourceContracts.CatalogEntry;
import tech.kayys.farabi.engine.DataSourceContracts.ColumnMetadata;
import tech.kayys.farabi.engine.DataSourceContracts.Descriptor;
import tech.kayys.farabi.engine.QueryContracts.ParameterizedPlan;
import tech.kayys.farabi.engine.QueryContracts.Submission;

/**
 * Compatibility ports for datasource integrations.
 *
 * <p>The canonical provider and catalog contracts live in DataSourceContracts;
 * query execution reuses the engine's parameterized plan and submission types.</p>
 */
public final class DataSourcePorts {
    private DataSourcePorts() {}

    public interface Registry {
        Optional<Descriptor> find(String sourceId);
        List<Descriptor> list();
    }

    public interface CatalogProvider {
        List<CatalogEntry> listObjects(String sourceId, String catalog, String schemaPattern);
        List<ColumnMetadata> listColumns(String sourceId, String catalog, String schema, String objectName);
    }

    /** Reports source capabilities; capability hints are never authorization decisions. */
    @FunctionalInterface
    public interface CapabilitiesProvider {
        DataSourceCapabilities capabilities(String sourceId);
    }

    /** Executes a plan only after the engine has applied authorization and policy validation. */
    @FunctionalInterface
    public interface QueryExecutor {
        Submission execute(ParameterizedPlan plan);
    }
}
