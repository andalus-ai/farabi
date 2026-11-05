package tech.kayys.farabi.jdbc.source;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import tech.kayys.farabi.engine.DataSourceContracts.ConnectionDefinition;
import tech.kayys.farabi.engine.DataSourcePorts.SecretResolver;

/**
 * Resolves a source's secret immediately before opening a JDBC connection.
 *
 * <p>This adapter deliberately requires a host-supplied connection policy. The policy
 * must validate the JDBC URL and driver properties (including TLS verification,
 * timeouts and allowed options) for the configured database vendor. It must fail closed;
 * this class has no permissive default.</p>
 *
 * <p>The secret reference is treated as the JDBC password. The optional {@code username}
 * connection property is translated to the JDBC {@code user} property. Raw credentials
 * are never written back to the connection definition.</p>
 */
public final class SecretBackedJdbcConnectionFactory implements JdbcSourceProvider.ConnectionFactory {
    @FunctionalInterface
    public interface ConnectionOpener {
        Connection open(String jdbcUrl, Properties properties) throws SQLException;
    }

    @FunctionalInterface
    public interface ConnectionPolicy {
        /**
         * Validate the endpoint and properties and apply mandatory vendor-specific
         * security options. Implementations must reject remote connections without
         * authenticated TLS (including certificate/hostname verification).
         */
        void validateAndApply(ConnectionDefinition definition, Properties properties);
    }

    private final SecretResolver secrets;
    private final ConnectionPolicy policy;
    private final ConnectionOpener opener;

    public SecretBackedJdbcConnectionFactory(SecretResolver secrets,
                                             ConnectionPolicy policy,
                                             ConnectionOpener opener) {
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.opener = Objects.requireNonNull(opener, "opener");
    }

    @Override
    public Connection open(ConnectionDefinition definition) throws SQLException {
        Objects.requireNonNull(definition, "definition");
        if (!"jdbc".equalsIgnoreCase(definition.endpoint().getScheme())) {
            throw new IllegalArgumentException("JDBC provider requires a jdbc: endpoint");
        }

        Properties properties = new Properties();
        for (Map.Entry<String, String> entry : definition.properties().entrySet()) {
            String key = entry.getKey();
            if ("password".equalsIgnoreCase(key) || "user".equalsIgnoreCase(key)) {
                throw new IllegalArgumentException(
                        "connection properties must use username and a secret reference, not raw JDBC credentials");
            }
            if ("username".equalsIgnoreCase(key)) {
                properties.setProperty("user", entry.getValue());
            } else {
                properties.setProperty(key, entry.getValue());
            }
        }

        char[] secret = null;
        try {
            if (definition.credential() != null) {
                secret = secrets.resolve(definition.credential().reference());
                if (secret == null || secret.length == 0) {
                    throw new IllegalStateException("configured JDBC credential could not be resolved");
                }
                properties.setProperty("password", new String(secret));
            }

            policy.validateAndApply(definition, properties);
            Connection connection = opener.open(definition.endpoint().toString(), properties);
            if (connection == null) {
                throw new IllegalStateException("JDBC connection opener returned null");
            }
            if (definition.readOnly()) {
                connection.setReadOnly(true);
            }
            return connection;
        } finally {
            // JDBC's Properties API requires immutable String values; remove the password
            // immediately after opening and always wipe the resolver-owned char[].
            properties.remove("password");
            if (secret != null) Arrays.fill(secret, '\0');
        }
    }
}
