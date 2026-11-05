package tech.kayys.farabi.jdbc.source;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import tech.kayys.farabi.engine.QueryContracts.*;
import tech.kayys.farabi.engine.QueryLifecycle;
import tech.kayys.farabi.engine.QueryPorts;

/**
 * Synchronous, bounded JDBC query adapter.
 *
 * The injected connection factory must resolve an already-authorized source ID to a
 * configured connection. Authorization and workspace scoping remain engine/runtime duties.
 * Results are held in a bounded process-local cache; this adapter is not a durable job store.
 */
public final class JdbcQueryProvider implements QueryPorts.Executor, QueryLifecycle.Provider {
    @FunctionalInterface
    public interface ConnectionFactory {
        Connection open(String sourceId) throws SQLException;
    }

    private record Job(List<String> columns, List<Map<String, Object>> rows, boolean cancelled) {}

    private final ConnectionFactory connections;
    private final int maxRows;
    private final int fetchSize;
    private final int maxPageSize;
    private final int maxCachedJobs;
    private final ConcurrentHashMap<String, Job> jobs = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<String> insertionOrder = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<String, Statement> activeStatements = new ConcurrentHashMap<>();

    public JdbcQueryProvider(ConnectionFactory connections, int maxRows, int fetchSize,
                             int maxPageSize, int maxCachedJobs) {
        this.connections = Objects.requireNonNull(connections, "connections");
        if (maxRows < 1 || fetchSize < 1 || maxPageSize < 1 || maxCachedJobs < 1) {
            throw new IllegalArgumentException("JDBC query limits must be positive");
        }
        this.maxRows = maxRows;
        this.fetchSize = fetchSize;
        this.maxPageSize = maxPageSize;
        this.maxCachedJobs = maxCachedJobs;
    }

    @Override public Submission submit(Plan plan) {
        Objects.requireNonNull(plan, "plan");
        String jobId = UUID.randomUUID().toString();
        try (Connection connection = connections.open(plan.sourceId())) {
            if (connection == null) throw new IllegalStateException("connection factory returned null");
            connection.setReadOnly(true);
            try (PreparedStatement statement = connection.prepareStatement(
                    plan.sql(), ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                statement.setMaxRows(maxRows);
                statement.setFetchSize(fetchSize);
                bind(statement, plan.parameters());
                activeStatements.put(jobId, statement);
                try (ResultSet result = statement.executeQuery()) {
                    ResultSetMetaData metadata = result.getMetaData();
                    List<String> columns = new ArrayList<>(metadata.getColumnCount());
                    for (int i = 1; i <= metadata.getColumnCount(); i++) {
                        columns.add(metadata.getColumnLabel(i));
                    }
                    List<Map<String, Object>> rows = new ArrayList<>();
                    while (result.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= metadata.getColumnCount(); i++) {
                            row.put(columns.get(i - 1), result.getObject(i));
                        }
                        rows.add(row);
                    }
                    jobs.put(jobId, new Job(List.copyOf(columns), List.copyOf(rows), false));
                    insertionOrder.add(jobId);
                    evictOverflow();
                    return new Submission(jobId);
                } finally {
                    activeStatements.remove(jobId);
                }
            }
        } catch (SQLException e) {
            activeStatements.remove(jobId);
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
        return new QueryLifecycle.RemoteState(
                job.cancelled() ? Status.CANCELLED : Status.SUCCEEDED, null);
    }

    @Override public QueryLifecycle.ResultPage results(String providerJobId, int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > maxPageSize) {
            throw new IllegalArgumentException("invalid result page request");
        }
        Job job = requireJob(providerJobId);
        if (job.cancelled()) throw new IllegalStateException("query was cancelled");
        int end = Math.min(job.rows().size(), Math.addExact(offset, limit));
        List<Map<String, Object>> page = offset >= job.rows().size()
                ? List.of() : job.rows().subList(offset, end);
        return new QueryLifecycle.ResultPage(job.columns(), page, offset, end < job.rows().size());
    }

    @Override public void cancel(String providerJobId) {
        Statement statement = activeStatements.get(providerJobId);
        if (statement != null) {
            try { statement.cancel(); }
            catch (SQLException e) { throw new IllegalStateException("JDBC query cancellation failed", e); }
        }
        jobs.compute(providerJobId, (id, job) -> {
            if (job == null) throw new IllegalArgumentException("unknown or expired JDBC query job");
            return new Job(job.columns(), job.rows(), true);
        });
    }

    private Job requireJob(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("providerJobId required");
        Job job = jobs.get(id);
        if (job == null) throw new IllegalArgumentException("unknown or expired JDBC query job");
        return job;
    }

    private void evictOverflow() {
        while (jobs.size() > maxCachedJobs) {
            String oldest = insertionOrder.poll();
            if (oldest == null) return;
            jobs.remove(oldest);
        }
    }
}
