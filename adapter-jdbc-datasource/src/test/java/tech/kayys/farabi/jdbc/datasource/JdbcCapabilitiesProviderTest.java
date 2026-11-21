package tech.kayys.farabi.jdbc.datasource;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.DataSourceContracts.Descriptor;

class JdbcCapabilitiesProviderTest {
    @Test
    void discoversCapabilitiesFromTheConnectedDriver() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:capabilities");
        var descriptor = new Descriptor("analytics", "jdbc", "Analytics", "h2");
        var registry = new JdbcDataSourceRegistry(Map.of(
                "analytics", new JdbcDataSourceRegistry.Entry(descriptor, source)));

        var capabilities = new JdbcCapabilitiesProvider(registry).capabilities("analytics");

        assertEquals("H2", capabilities.productName());
        assertFalse(capabilities.driverName().isBlank());
        assertNotNull(capabilities.identifierQuoteString());
        assertTrue(capabilities.supportsTransactions());
        assertTrue(capabilities.maxColumnsInTable() >= 0);
    }

    @Test
    void rejectsUnknownSource() {
        var provider = new JdbcCapabilitiesProvider(new JdbcDataSourceRegistry(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> provider.capabilities("missing"));
    }
}
