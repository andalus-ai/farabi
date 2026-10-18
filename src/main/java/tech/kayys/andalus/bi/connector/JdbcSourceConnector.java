package tech.kayys.andalus.bi.connector;

import tech.kayys.andalus.bi.query.JdbcQueryExecutor;
import tech.kayys.andalus.bi.query.QueryExecutor;
import tech.kayys.andalus.bi.query.QueryPlan;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * JDBC adapter backed by administrator-provisioned, read-only DataSources.
 * DataSources are keyed by trusted secret references; no arbitrary JDBC URL
 * or credential is accepted from user requests.
 */
public final class JdbcSourceConnector implements SourceConnector {
    private final Map<String, DataSource> provisionedSources;
    private final int maxRows;

    public JdbcSourceConnector(Map<String, DataSource> provisionedSources, int maxRows) {
        this.provisionedSources = Map.copyOf(Objects.requireNonNull(provisionedSources));
        if (maxRows < 1) throw new IllegalArgumentException("maxRows must be positive");
        this.maxRows = maxRows;
    }

    @Override public String type() { return "jdbc"; }

    @Override public ConnectorCapabilities capabilities() {
        return new ConnectorCapabilities(true, true, false, false, maxRows);
    }

    @Override public QueryExecutor.QueryResult execute(SourceDescriptor source, QueryPlan plan, Duration timeout) {
        DataSource dataSource = provisionedSources.get(source.secretRef());
        if (dataSource == null) throw new IllegalArgumentException("source credentials are not provisioned");
        if (!source.id().equals(plan.sourceId())) throw new IllegalArgumentException("plan/source mismatch");
        return new JdbcQueryExecutor(dataSource).execute(plan, timeout);
    }
}
