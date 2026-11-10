package tech.kayys.farabi.jdbc.datasource;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.DataSourceContracts.Descriptor;
import tech.kayys.farabi.engine.SemanticQueryContracts.*;
import tech.kayys.farabi.engine.SemanticQueryPlanCompiler;

class JdbcSemanticQueryExecutorTest {
    @Test
    void compilesAndExecutesOnlyScopedParameterizedSemanticQuery() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:semantic_bridge;DB_CLOSE_DELAY=-1");
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE sales (region VARCHAR(40), amount DECIMAL(12,2), status VARCHAR(20))");
            statement.execute("INSERT INTO sales VALUES ('west', 12.50, 'approved'), ('west', 7.50, 'approved'), ('east', 9.00, 'pending')");
        }
        var registry = new JdbcDataSourceRegistry(Map.of("sales-source",
                new JdbcDataSourceRegistry.Entry(new Descriptor("sales-source", "jdbc", "Sales DB", "h2"), source)));
        AtomicReference<String> scope = new AtomicReference<>();
        var compiler = new SemanticQueryPlanCompiler((workspace, subject, dataset) -> {
            scope.set(workspace + "/" + subject + "/" + dataset);
            return new SemanticQueryPlanCompiler.Dataset("sales-source", "h2", "sales", Map.of(
                    "region", new SemanticQueryPlanCompiler.Field("region", "region", FieldType.TEXT, true, false),
                    "amount", new SemanticQueryPlanCompiler.Field("amount", "amount", FieldType.DECIMAL, false, true),
                    "status", new SemanticQueryPlanCompiler.Field("status", "status", FieldType.TEXT, false, false)));
        }, new SemanticQueryPlanCompiler.Dialect() {
            public String id() { return "h2"; }
            public String quoteIdentifier(String identifier) { return "\"" + identifier.replace("\"", "\"\"") + "\""; }
            public String renderLimit(int limit) { return "FETCH FIRST " + limit + " ROWS ONLY"; }
        });
        var adapter = new JdbcSemanticQueryExecutor(compiler, new JdbcQueryExecutor(registry));
        Query query = new Query("sales-dataset", List.of(new FieldRef("region")),
                List.of(new Measure(new FieldRef("amount"), Aggregation.SUM, "total")),
                List.of(new Predicate(new FieldRef("status"), Operator.EQ, FieldType.TEXT, "approved")), 10);

        List<Map<String, Object>> rows = adapter.execute("workspace-1", "subject-9", query);

        assertEquals("workspace-1/subject-9/sales-dataset", scope.get());
        assertEquals(1, rows.size());
        assertEquals("west", rows.get(0).get("region"));
        assertEquals(new BigDecimal("20.00"), ((BigDecimal) rows.get(0).get("total")).setScale(2));
    }

    @Test
    void rejectsMetadataResolvedForDifferentDialectBeforeExecuting() {
        var compiler = new SemanticQueryPlanCompiler((workspace, subject, dataset) ->
                new SemanticQueryPlanCompiler.Dataset("sales-source", "postgres", "sales", Map.of(
                        "region", new SemanticQueryPlanCompiler.Field("region", "region", FieldType.TEXT, true, false))),
                new SemanticQueryPlanCompiler.Dialect() {
                    public String id() { return "h2"; }
                    public String quoteIdentifier(String identifier) { return "\"" + identifier + "\""; }
                    public String renderLimit(int limit) { return "FETCH FIRST " + limit + " ROWS ONLY"; }
                });
        var adapter = new JdbcSemanticQueryExecutor(compiler, new JdbcQueryExecutor(new JdbcDataSourceRegistry(Map.of())));
        Query query = new Query("sales-dataset", List.of(new FieldRef("region")), List.of(), List.of(), 10);
        assertThrows(IllegalArgumentException.class, () -> adapter.execute("workspace", "subject", query));
    }
}
