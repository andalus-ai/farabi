package tech.kayys.farabi.jdbc.source;

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
import java.util.UUID;
import tech.kayys.farabi.engine.QueryContracts.*;
import tech.kayys.farabi.engine.QueryLifecycle;
import tech.kayys.farabi.engine.QueryPorts;

/**
 * Synchronous, bounded JDBC query adapter.
 *
 * The injected connection factory must resolve an already-authorized source ID to a
 * configured connection using a read-only database principal. Connection.setReadOnly(true)
 * is only a JDBC hint and is not a substitute for database-enforced least privilege.
 * Authorization and workspace scoping remain engine/runtime duties. SQL must come from
 * Farabi's governed compiler; user values must be represented as Plan parameters.
 * Results are held in a bounded process-local cache; this adapter is not a durable job store.
 */
public final class JdbcQueryProvider implements QueryPorts.Executor, QueryLifecycle.Provider {
    @FunctionalInterface
    public interface ConnectionFactory {
        Connection open(String sourceId, String workspaceId, String subjectId) throws SQLException;
    }

    private record Job(List<String> columns, List<Map<String, Object>> rows, boolean truncated) {}

    private final ConnectionFactory connections;
    private final int maxRows;
    private final int fetchSize;
    private final int maxPageSize;
    private final int maxCachedJobs;
    private final Map<String, Job> jobs = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(16, 0.75f, true));

    public JdbcQueryProvider(ConnectionFactory connections, int maxRows, int fetchSize,
                             int maxPageSize, int maxCachedJobs) {
        this.connections = Objects.requireNonNull(connections, "connections");
        if (maxRows < 1 || maxRows == Integer.MAX_VALUE || fetchSize < 1 || maxPageSize < 1 || maxCachedJobs < 1) {
            throw new IllegalArgumentException("JDBC query limits must be positive");
        }
        this.maxRows = maxRows;
        this.fetchSize = fetchSize;
        this.maxPageSize = maxPageSize;
        this.maxCachedJobs = maxCachedJobs;
    }

    @Override public Submission submit(Plan plan) {
        Objects.requireNonNull(plan, "plan");
        if (plan.workspaceId() == null || plan.subjectId() == null) {
            throw new SecurityException("governed JDBC execution requires authenticated workspace and subject context");
        }
        String jobId = UUID.randomUUID().toString();
        try (Connection connection = connections.open(plan.sourceId(), plan.workspaceId(), plan.subjectId())) {
            if (connection == null) throw new IllegalStateException("connection factory returned null");
            connection.setReadOnly(true);
            try (PreparedStatement statement = connection.prepareStatement(
                    plan.sql(), ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                statement.setMaxRows(Math.addExact(maxRows, 1));
                statement.setQueryTimeout(30);
                statement.setFetchSize(fetchSize);
                bind(statement, plan.parameters());
                try (ResultSet result = statement.executeQuery()) {
                    ResultSetMetaData metadata = result.getMetaData();
                    List<String> columns = new ArrayList<>(metadata.getColumnCount());
                    for (int i = 1; i <= metadata.getColumnCount(); i++) {
                        columns.add(metadata.getColumnLabel(i));
                    }
                    List<Map<String, Object>> rows = new ArrayList<>();
                    boolean truncated = false;
                    while (result.next()) {
                        if (rows.size() == maxRows) {
                            truncated = true;
                            break;
                        }
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= metadata.getColumnCount(); i++) {
                            row.put(columns.get(i - 1), result.getObject(i));
                        }
                        rows.add(row);
                    }
                    synchronized (jobs) {
                        jobs.put(jobId, new Job(List.copyOf(columns), List.copyOf(rows), truncated));
                        while (jobs.size() > maxCachedJobs) {
                            String eldest = jobs.keySet().iterator().next();
                            jobs.remove(eldest);
                        }
                    }
                    return new Submission(jobId);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("JDBC query failed (SQLState " + e.getSQLState() + ")", e);
        }
    }

    private static void bind(PreparedStatement statement, List<Parameter> parameters) throws SQLException {
        for (Parameter parameter : parameters) {
            Object value = parameter.value();
            if (value == null) {
                if (parameter.jdbcType() == null) {
                    throw new IllegalArgumentException("NULL query parameter requires an explicit JDBC type");
                }
                statement.setNull(parameter.position(), parameter.jdbcType());
            } else if (parameter.jdbcType() == null) {
                statement.setObject(parameter.position(), value);
            } else {
                statement.setObject(parameter.position(), value, parameter.jdbcType());
            }
        }
    }

    @Override public QueryLifecycle.RemoteState status(String providerJobId) {
        Job job = requireJob(providerJobId);
        return new QueryLifecycle.RemoteState(Status.SUCCEEDED, null);
    }

    @Override public QueryLifecycle.ResultPage results(String providerJobId, int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > maxPageSize) {
            throw new IllegalArgumentException("invalid result page request");
        }
        Job job = requireJob(providerJobId);
        int end = Math.min(job.rows().size(), Math.addExact(offset, limit));
        List<Map<String, Object>> page = offset >= job.rows().size()
                ? List.of() : job.rows().subList(offset, end);
        return new QueryLifecycle.ResultPage(job.columns(), page, offset, end < job.rows().size(),
                job.truncated() && offset < job.rows().size() && end == job.rows().size());
    }

    @Override public void cancel(String providerJobId) {
        throw new UnsupportedOperationException(
                "synchronous JDBC execution cannot be cancelled after submission; use the async worker adapter");
    }

    private Job requireJob(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("providerJobId required");
        Job job = jobs.get(id);
        if (job == null) throw new IllegalArgumentException("unknown or expired JDBC query job");
        return job;
    }

}
