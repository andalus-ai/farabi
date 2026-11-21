package tech.kayys.farabi.jdbc.datasource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import tech.kayys.farabi.engine.QueryContracts.ParameterizedPlan;
import tech.kayys.farabi.engine.QueryContracts.QueryParameter;
import tech.kayys.farabi.engine.QueryContracts.ParameterType;

/**
 * Bounded synchronous JDBC query execution for trusted, compiled parameterized plans.
 * Source registration and workspace-level authorization must happen before this adapter is called.
 */
public final class JdbcQueryExecutor {
    public record Result(List<String> columns, List<Map<String, Object>> rows, boolean truncated) {
        public Result {
            columns = List.copyOf(columns);
            rows = List.copyOf(rows.stream()
                    .map(row -> Collections.unmodifiableMap(new LinkedHashMap<>(row))).toList());
        }
    }

    private final JdbcDataSourceRegistry registry;
    private final int maximumRows;

    public JdbcQueryExecutor(JdbcDataSourceRegistry registry) {
        this(registry, 10000);
    }

    public JdbcQueryExecutor(JdbcDataSourceRegistry registry, int maximumRows) {
        this.registry = Objects.requireNonNull(registry, "registry");
        if (maximumRows < 1 || maximumRows > 10000) throw new IllegalArgumentException("maximumRows must be 1..10000");
        this.maximumRows = maximumRows;
    }

    public Result execute(ParameterizedPlan plan) {
        Objects.requireNonNull(plan, "plan");
        DataSource source = registry.requireDataSource(plan.sourceId());
        try (Connection connection = source.getConnection()) {
            connection.setReadOnly(true);
            try (PreparedStatement statement = connection.prepareStatement(plan.sqlTemplate())) {
                statement.setMaxRows(maximumRows + 1);
                statement.setQueryTimeout(60);
                for (int i = 0; i < plan.parameters().size(); i++)
                    bind(statement, i + 1, plan.parameters().get(i));
                try (ResultSet rs = statement.executeQuery()) {
                    ResultSetMetaData metadata = rs.getMetaData();
                    List<String> columns = new ArrayList<>();
                    for (int i = 1; i <= metadata.getColumnCount(); i++) columns.add(metadata.getColumnLabel(i));
                    List<Map<String, Object>> rows = new ArrayList<>();
                    while (rs.next() && rows.size() <= maximumRows) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= columns.size(); i++) row.put(columns.get(i - 1), rs.getObject(i));
                        rows.add(row);
                    }
                    boolean truncated = rows.size() > maximumRows;
                    if (truncated) rows.remove(rows.size() - 1);
                    return new Result(columns, rows, truncated);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("JDBC query execution failed for source " + plan.sourceId(), e);
        }
    }

    private static void bind(PreparedStatement statement, int index, QueryParameter parameter) throws SQLException {
        switch (parameter.type()) {
            case TEXT -> statement.setString(index, (String) parameter.value());
            case DECIMAL -> statement.setBigDecimal(index, (java.math.BigDecimal) parameter.value());
            case DATE -> statement.setObject(index, (java.time.LocalDate) parameter.value());
            case BOOLEAN -> statement.setBoolean(index, (Boolean) parameter.value());
        }
    }
}
