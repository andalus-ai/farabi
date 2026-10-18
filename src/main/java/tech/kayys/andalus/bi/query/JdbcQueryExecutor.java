package tech.kayys.andalus.bi.query;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * JDBC implementation for a trusted, read-only DataSource.
 * Use a dedicated DB role with SELECT-only permissions, row-level policy,
 * statement timeout, and resource limits; a SELECT-only compiler is not
 * sufficient to enforce database security.
 */
public final class JdbcQueryExecutor implements QueryExecutor {
    private final DataSource dataSource;

    public JdbcQueryExecutor(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
    }

    @Override public QueryResult execute(QueryPlan plan, Duration timeout) {
        Objects.requireNonNull(plan);
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("positive timeout required");
        }
        long seconds = Math.max(1, (timeout.toMillis() + 999) / 1000);
        if (seconds > 300) throw new IllegalArgumentException("timeout exceeds 300 seconds");
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            try (var statement = connection.prepareStatement(plan.sql())) {
                statement.setQueryTimeout((int) seconds);
                statement.setMaxRows(plan.limit());
                for (int i = 0; i < plan.parameters().size(); i++) {
                    statement.setObject(i + 1, plan.parameters().get(i));
                }
                try (var rs = statement.executeQuery()) {
                    var rows = new ArrayList<Map<String, Object>>();
                    ResultSetMetaData metadata = rs.getMetaData();
                    while (rs.next()) {
                        var row = new LinkedHashMap<String, Object>();
                        for (int i = 1; i <= metadata.getColumnCount(); i++) {
                            row.put(metadata.getColumnLabel(i), rs.getObject(i));
                        }
                        rows.add(Map.copyOf(row));
                    }
                    return new QueryResult(rows);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("semantic query execution failed", e);
        }
    }
}
