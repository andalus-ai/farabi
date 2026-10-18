package tech.kayys.andalus.bi.connector;

import tech.kayys.andalus.bi.query.QueryExecutor;
import tech.kayys.andalus.bi.query.QueryPlan;

import java.time.Duration;
import java.util.List;

/** Source-specific execution and discovery boundary. */
public interface SourceConnector {
    String type();
    ConnectorCapabilities capabilities();
    QueryExecutor.QueryResult execute(SourceDescriptor source, QueryPlan plan, Duration timeout);

    /** Implementations should return governed, access-filtered metadata only. */
    default List<TableMetadata> discover(SourceDescriptor source) {
        throw new UnsupportedOperationException("schema discovery unsupported");
    }

    record TableMetadata(String name, List<String> columns) {
        public TableMetadata { columns = List.copyOf(columns); }
    }
}
