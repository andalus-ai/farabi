package tech.kayys.farabi.quarkus;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import tech.kayys.farabi.engine.DataSourceContracts.ConnectionDefinition;
import tech.kayys.farabi.jdbc.source.SecretBackedJdbcConnectionFactory.ConnectionPolicy;

/** Fail-closed outbound JDBC policy for the first PostgreSQL/MySQL runtime slice. */
final class AllowlistedJdbcConnectionPolicy implements ConnectionPolicy {
    private final Set<String> allowedHosts;

    AllowlistedJdbcConnectionPolicy(String configuredHosts) {
        Objects.requireNonNull(configuredHosts, "configuredHosts");
        this.allowedHosts = Stream.of(configuredHosts.split(","))
                .map(String::trim).filter(host -> !host.isEmpty())
                .map(host -> host.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(() -> new TreeSet<>()));
    }

    @Override
    public void validateAndApply(ConnectionDefinition definition, java.util.Properties properties) {
        String jdbcUrl = definition.endpoint().toString();
        if (!(jdbcUrl.startsWith("jdbc:postgresql://") || jdbcUrl.startsWith("jdbc:mysql://"))) {
            throw new IllegalArgumentException("only PostgreSQL and MySQL JDBC endpoints are supported");
        }
        URI uri = URI.create(jdbcUrl.substring("jdbc:".length()));
        String host = uri.getHost();
        if (host == null || uri.getUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || !allowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
            throw new SecurityException("JDBC endpoint host is not allowlisted or endpoint contains unsafe options");
        }
        if (!definition.readOnly()) {
            throw new SecurityException("analytical JDBC sources must be read-only");
        }
        for (String key : properties.stringPropertyNames()) {
            if (!key.equals("user") && !key.equals("password")) {
                throw new IllegalArgumentException("unsupported JDBC connection property: " + key);
            }
        }
        if (jdbcUrl.startsWith("jdbc:postgresql://")) {
            properties.setProperty("sslmode", "verify-full");
        } else {
            properties.setProperty("sslMode", "VERIFY_IDENTITY");
        }
    }
}
