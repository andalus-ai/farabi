package tech.kayys.farabi.jdbc.datasource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tech.kayys.farabi.engine.DataSourceContracts.QueryRequest;
import tech.kayys.farabi.engine.DataSourceContracts.QueryResult;
import tech.kayys.farabi.engine.DataSourcePorts.QueryExecutor;

/**
 * Bounded JDBC query execution. The caller must authorize and compile the request
 * before invoking this adapter; database credentials should be read-only.
 */
public final class JdbcQueryExecutor implements QueryExecutor {
    private final JdbcDataSourceRegistry registry;

    public JdbcQueryExecutor(JdbcDataSourceRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override public QueryResult execute(QueryRequest request) {
        Objects.requireNonNull(request, "request");
        var descriptor = registry.find(request.sourceId())
                .orElseThrow(() -> new IllegalArgumentException("unknown data source: " + request.sourceId()));
        if (!descriptor.readOnly()) {
            throw new SecurityException("analytics JDBC execution requires a read-only source registration");
        }
        try (Connection connection = registry.requireDataSource(request.sourceId()).getConnection()) {
            // A connection-level read-only hint complements, but never replaces, read-only DB credentials.
            connection.setReadOnly(true);
            try (PreparedStatement statement = connection.prepareStatement(request.sql())) {
            statement.setMaxRows(request.maxRows() + 1);
            statement.setQueryTimeout(60);
            for (int i = 0; i < request.parameters().size(); i++) {
                statement.setObject(i + 1, request.parameters().get(i));
            }
            try (ResultSet rs = statement.executeQuery()) {
                ResultSetMetaData metadata = rs.getMetaData();
                List<String> columns = new ArrayList<>();
                for (int i = 1; i <= metadata.getColumnCount(); i++) columns.add(metadata.getColumnLabel(i));
                List<Map<String, Object>> rows = new ArrayList<>();
                while (rs.next() && rows.size() <= request.maxRows()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= columns.size(); i++) row.put(columns.get(i - 1), rs.getObject(i));
                    rows.add(java.util.Collections.unmodifiableMap(row));
                }
                boolean truncated = rows.size() > request.maxRows();
                if (truncated) rows.remove(rows.size() - 1);
                return new QueryResult(columns, rows, truncated);
            }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("JDBC query execution failed for source " + request.sourceId(), e);
        }
    }
}
