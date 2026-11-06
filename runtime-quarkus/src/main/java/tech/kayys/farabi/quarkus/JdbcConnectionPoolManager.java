package tech.kayys.farabi.quarkus;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import tech.kayys.farabi.engine.DataSourceContracts.DataSourceDescriptor;
import tech.kayys.farabi.engine.DataSourceContracts.ProviderKind;
import tech.kayys.farabi.jdbc.datasource.JdbcDataSourceRegistry;

/**
 * Runtime-owned lifecycle for named customer JDBC pools.
 *
 * Only PostgreSQL and MySQL URLs are accepted in this first slice. Hosts must be
 * explicitly allowlisted; TLS verification is forced by the driver properties.
 */
public final class JdbcConnectionPoolManager implements AutoCloseable {
    private final Map<String, HikariDataSource> pools;
    private final JdbcDataSourceRegistry registry;

    public JdbcConnectionPoolManager(
            Map<String, SourceDefinition> definitions,
            Set<String> allowedHosts,
            SecretResolver secrets) {
        Objects.requireNonNull(definitions, "definitions");
        Objects.requireNonNull(allowedHosts, "allowedHosts");
        if (definitions.size() > 32
                || definitions.values().stream().mapToInt(SourceDefinition::maximumPoolSize).sum() > 200) {
            throw new IllegalArgumentException("JDBC source limits exceeded (32 sources / 200 pooled connections)");
        }
        if (!definitions.isEmpty() && secrets == null) {
            throw new IllegalStateException("a SecretResolver is required when JDBC sources are configured");
        }

        Set<String> hosts = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        allowedHosts.stream().filter(Objects::nonNull).map(String::trim)
                .filter(host -> !host.isEmpty()).forEach(hosts::add);
        if (!definitions.isEmpty() && hosts.isEmpty()) {
            throw new IllegalArgumentException("farabi.sources.allowed-hosts must allowlist JDBC hosts");
        }

        Map<String, HikariDataSource> created = new LinkedHashMap<>();
        Map<String, JdbcDataSourceRegistry.Entry> entries = new LinkedHashMap<>();
        try {
            definitions.forEach((id, definition) -> {
                validateSourceId(id);
                Objects.requireNonNull(definition, "source definition");
                String scheme = validateJdbcUrl(definition.url(), hosts);
                validatePoolSettings(definition);
                String password = Objects.requireNonNull(
                        secrets.resolve(definition.passwordSecretRef()), "resolved JDBC password");
                if (password.isBlank()) throw new IllegalStateException("resolved JDBC password is blank for " + id);

                HikariConfig config = new HikariConfig();
                config.setPoolName("farabi-" + id);
                config.setJdbcUrl(definition.url());
                config.setUsername(definition.username());
                config.setPassword(password);
                config.setMaximumPoolSize(definition.maximumPoolSize());
                config.setMinimumIdle(definition.minimumIdle());
                config.setConnectionTimeout(definition.connectionTimeoutMs());
                config.setInitializationFailTimeout(-1);
                config.setReadOnly(definition.readOnly());
                config.setAutoCommit(true);
                config.setRegisterMbeans(false);

                if (definition.tlsRequired()) {
                    if ("postgresql".equals(scheme)) {
                        config.addDataSourceProperty("sslmode", "verify-full");
                    } else if ("mysql".equals(scheme)) {
                        config.addDataSourceProperty("sslMode", "VERIFY_IDENTITY");
                    }
                }

                HikariDataSource pool = new HikariDataSource(config);
                created.put(id, pool);
                DataSourceDescriptor descriptor = new DataSourceDescriptor(
                        id, definition.displayName(), ProviderKind.JDBC, definition.readOnly());
                entries.put(id, new JdbcDataSourceRegistry.Entry(descriptor, pool));
            });
        } catch (RuntimeException failure) {
            created.values().forEach(HikariDataSource::close);
            throw failure;
        }
        this.pools = Map.copyOf(created);
        this.registry = new JdbcDataSourceRegistry(entries);
    }

    public JdbcDataSourceRegistry registry() {
        return registry;
    }

    public int poolCount() {
        return pools.size();
    }

    @Override
    public void close() {
        pools.values().forEach(HikariDataSource::close);
    }

    private static void validateSourceId(String id) {
        if (id == null || !id.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,62}")) {
            throw new IllegalArgumentException("invalid JDBC source id");
        }
    }

    private static String validateJdbcUrl(String jdbcUrl, Set<String> allowedHosts) {
        if (jdbcUrl == null || !(jdbcUrl.startsWith("jdbc:postgresql://") || jdbcUrl.startsWith("jdbc:mysql://"))) {
            throw new IllegalArgumentException("only jdbc:postgresql:// and jdbc:mysql:// URLs are supported");
        }
        try {
            URI uri = URI.create(jdbcUrl.substring("jdbc:".length()));
            String host = uri.getHost();
            if (host == null || uri.getUserInfo() != null || !allowedHosts.contains(host)
                    || hasCredentialOrTlsOverride(uri.getRawQuery())) {
                throw new IllegalArgumentException("JDBC URL host must be explicitly allowlisted and contain no user-info");
            }
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("postgresql") && !scheme.equals("mysql")) {
                throw new IllegalArgumentException("unsupported JDBC scheme");
            }
            return scheme;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid or disallowed JDBC URL", e);
        }
    }

    private static boolean hasCredentialOrTlsOverride(String query) {
        if (query == null || query.isBlank()) return false;
        for (String pair : query.split("&")) {
            String key = pair.split("=", 2)[0].toLowerCase(Locale.ROOT);
            if (key.equals("user") || key.equals("username") || key.equals("password")
                    || key.equals("sslmode") || key.equals("ssl_mode") || key.equals("usessl")
                    || key.equals("requiressl") || key.equals("verifyservercertificate")
                    || key.equals("socketfactory") || key.equals("socketfactoryarg")
                    || key.equals("socket") || key.equals("localsocketaddress")) return true;
        }
        return false;
    }

    private static void validatePoolSettings(SourceDefinition definition) {
        if (definition.displayName() == null || definition.displayName().isBlank()
                || definition.username() == null || definition.username().isBlank()
                || definition.passwordSecretRef() == null || definition.passwordSecretRef().isBlank()) {
            throw new IllegalArgumentException("JDBC display name, username and password secret reference are required");
        }
        if (definition.maximumPoolSize() < 1 || definition.maximumPoolSize() > 50
                || definition.minimumIdle() < 0 || definition.minimumIdle() > definition.maximumPoolSize()
                || definition.connectionTimeoutMs() < 250 || definition.connectionTimeoutMs() > 120_000) {
            throw new IllegalArgumentException("invalid JDBC pool limits");
        }
        if (!definition.readOnly()) {
            throw new IllegalArgumentException("analytical JDBC sources must use read-only credentials and pools");
        }
        if (!definition.tlsRequired()) {
            throw new IllegalArgumentException("customer JDBC connections must require TLS");
        }
    }

    public record SourceDefinition(
            String displayName,
            String url,
            String username,
            String passwordSecretRef,
            boolean readOnly,
            boolean tlsRequired,
            int maximumPoolSize,
            int minimumIdle,
            long connectionTimeoutMs) {
    }

    public static JdbcConnectionPoolManager from(
            JdbcSourcesConfig config, SecretResolver secrets) {
        Map<String, SourceDefinition> definitions = new LinkedHashMap<>();
        config.jdbc().forEach((id, source) -> definitions.put(id, new SourceDefinition(
                source.displayName(), source.url(), source.username(), source.passwordSecretRef(),
                source.readOnly(), source.tlsRequired(), source.maximumPoolSize(),
                source.minimumIdle(), source.connectionTimeoutMs())));
        return new JdbcConnectionPoolManager(definitions, Set.copyOf(config.allowedHosts()), secrets);
    }
}
