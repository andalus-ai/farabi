package tech.kayys.farabi.jdbc.source;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.QueryContracts.Parameter;
import tech.kayys.farabi.engine.QueryContracts.Plan;
import tech.kayys.farabi.engine.QueryContracts.Status;

class JdbcQueryProviderTest {
    private JdbcQueryProvider provider;
    private final AtomicBoolean readOnlyApplied = new AtomicBoolean();

    @BeforeEach void setUp() throws Exception {
        String url = "jdbc:h2:mem:query;DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS orders (id BIGINT PRIMARY KEY, amount DECIMAL(10,2), note VARCHAR(40))");
            s.execute("MERGE INTO orders KEY(id) VALUES (1, 12.50, 'first'), (2, 25.00, NULL), (3, 37.50, 'third')");
        }
        provider = new JdbcQueryProvider(sourceId -> {
            Connection delegate = DriverManager.getConnection(url);
            return (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("setReadOnly")) readOnlyApplied.set((Boolean) args[0]);
                        try { return method.invoke(delegate, args); }
                        catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
                    });
        }, 100, 50, 25, 20);
    }

    @Test void bindsParametersAndReturnsPagedRowsIncludingNulls() {
        var submission = provider.submit(new Plan("orders",
                "SELECT id, amount, note FROM orders WHERE id >= ? ORDER BY id",
                List.of(new Parameter(1, 2L, java.sql.Types.BIGINT))));
        assertEquals(Status.SUCCEEDED, provider.status(submission.providerJobId()).status());
        var firstPage = provider.results(submission.providerJobId(), 0, 1);
        assertEquals(1, firstPage.rows().size());
        assertEquals(2L, ((Number) firstPage.rows().get(0).get("ID")).longValue());
        assertNull(firstPage.rows().get(0).get("NOTE"));
        assertTrue(firstPage.hasMore());
        var secondPage = provider.results(submission.providerJobId(), 1, 1);
        assertEquals(3L, ((Number) secondPage.rows().get(0).get("ID")).longValue());
        assertFalse(secondPage.hasMore());
        assertTrue(readOnlyApplied.get());
    }

    @Test void rejectsNullParameterWithoutJdbcType() {
        var plan = new Plan("orders", "SELECT id FROM orders WHERE note = ?",
                List.of(new Parameter(1, null, null)));
        assertThrows(IllegalArgumentException.class, () -> provider.submit(plan));
    }

    @Test void rejectsNonContiguousParameterPositions() {
        assertThrows(IllegalArgumentException.class, () -> new Plan("orders", "SELECT ?",
                List.of(new Parameter(2, "x", java.sql.Types.VARCHAR))));
    }

    @Test void reportsWhenConfiguredRowLimitTruncatesResults() {
        var limited = new JdbcQueryProvider(sourceId -> DriverManager.getConnection(
                "jdbc:h2:mem:query;DB_CLOSE_DELAY=-1"), 2, 20, 10, 5);
        var submission = limited.submit(new Plan("orders", "SELECT id FROM orders ORDER BY id"));
        var page = limited.results(submission.providerJobId(), 0, 10);
        assertEquals(2, page.rows().size());
        assertTrue(page.truncated());
        assertFalse(page.hasMore());
    }

    @Test void boundsPageSizeAndEvictsOldCachedJobs() {
        var bounded = new JdbcQueryProvider(sourceId -> DriverManager.getConnection(
                "jdbc:h2:mem:query;DB_CLOSE_DELAY=-1"), 100, 20, 10, 1);
        var first = bounded.submit(new Plan("orders", "SELECT id FROM orders ORDER BY id"));
        var second = bounded.submit(new Plan("orders", "SELECT id FROM orders ORDER BY id"));
        assertThrows(IllegalArgumentException.class,
                () -> bounded.results(second.providerJobId(), 0, 11));
        assertThrows(IllegalArgumentException.class,
                () -> bounded.results(first.providerJobId(), 0, 1));
    }
}
