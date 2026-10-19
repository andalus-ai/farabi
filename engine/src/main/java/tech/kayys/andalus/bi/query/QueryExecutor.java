package tech.kayys.andalus.bi.query;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Replaceable execution port; implementations enforce tenant policy and timeouts. */
public interface QueryExecutor {
    record QueryResult(List<Map<String, Object>> rows) {
        public QueryResult { rows = List.copyOf(rows); }
    }

    QueryResult execute(QueryPlan plan, Duration timeout);
}
