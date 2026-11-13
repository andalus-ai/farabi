package tech.kayys.farabi.jdbc.source;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.sql.Connection;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.DataSourceContracts.ConnectionDefinition;
import tech.kayys.farabi.engine.DataSourceContracts.ProviderKind;
import tech.kayys.farabi.engine.DataSourceContracts.SecretReference;

class SecretBackedJdbcConnectionFactoryTest {
    private static ConnectionDefinition definition(Map<String, String> properties,
                                                    SecretReference credential,
                                                    boolean readOnly) {
        return new ConnectionDefinition("sales", "workspace-a", "Sales DB", ProviderKind.JDBC,
                "jdbc", URI.create("jdbc:postgresql://db.example.test:5432/sales"),
                null, null, properties, credential, readOnly, Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static Connection connection() {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("isClosed")) return false;
                    if (method.getName().equals("isReadOnly")) return true;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    return null;
                });
    }

    @Test
    void resolvesSecretOnlyForOpeningAndWipesResolverBuffer() throws Exception {
        char[] secret = "not-a-real-password".toCharArray();
        Properties[] observed = new Properties[1];
        AtomicBoolean policyCalled = new AtomicBoolean();

        var factory = new SecretBackedJdbcConnectionFactory(
                reference -> {
                    assertEquals("vault://team/sales", reference);
                    return secret;
                },
                (source, properties) -> {
                    policyCalled.set(true);
                    assertEquals("verify-full", properties.getProperty("sslmode"));
                },
                (url, properties) -> {
                    assertEquals("jdbc:postgresql://db.example.test:5432/sales", url);
                    assertEquals("analyst", properties.getProperty("user"));
                    assertEquals("not-a-real-password", properties.getProperty("password"));
                    observed[0] = properties;
                    return connection();
                });

        Connection opened = factory.open(definition(
                Map.of("username", "analyst", "sslmode", "verify-full"),
                new SecretReference("vault://team/sales"), true));

        assertNotNull(opened);
        assertTrue(policyCalled.get());
        assertArrayEquals(new char[secret.length], secret);
        assertFalse(observed[0].containsKey("password"));
    }

    @Test
    void rejectsRawCredentialProperties() {
        var factory = new SecretBackedJdbcConnectionFactory(
                reference -> new char[0], (source, properties) -> {},
                (url, properties) -> connection());

        assertThrows(IllegalArgumentException.class, () ->
                factory.open(definition(Map.of("password", "inline-secret"), null, true)));
        assertThrows(IllegalArgumentException.class, () ->
                factory.open(definition(Map.of("user", "analyst"), null, true)));
    }

    @Test
    void policyCanFailClosedBeforeAnyConnectionIsOpened() {
        AtomicBoolean openerCalled = new AtomicBoolean();
        var factory = new SecretBackedJdbcConnectionFactory(
                reference -> "secret".toCharArray(),
                (source, properties) -> {
                    throw new SecurityException("TLS verification is required");
                },
                (url, properties) -> {
                    openerCalled.set(true);
                    return connection();
                });

        assertThrows(SecurityException.class, () ->
                factory.open(definition(Map.of(), new SecretReference("vault://db"), true)));
        assertFalse(openerCalled.get());
    }

    @Test
    void rejectsNonJdbcEndpoint() {
        var factory = new SecretBackedJdbcConnectionFactory(
                reference -> new char[0], (source, properties) -> {},
                (url, properties) -> connection());
        var invalid = new ConnectionDefinition("sales", "workspace-a", "Sales DB", ProviderKind.JDBC,
                "jdbc", URI.create("https://db.example.test"), null, null, Map.of(), null,
                true, Instant.now());
        assertThrows(IllegalArgumentException.class, () -> factory.open(invalid));
    }
}
