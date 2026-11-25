package tech.kayys.farabi.quarkus;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.net.http.HttpClient;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Optional;
import javax.sql.DataSource;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import tech.kayys.farabi.dremio.DremioSourceFactory;
import tech.kayys.farabi.dremio.DremioSourceProvider;
import tech.kayys.farabi.engine.DataSourcePorts.ConnectionAuthorizer;
import tech.kayys.farabi.engine.DataSourcePorts.ConnectionRegistry;
import tech.kayys.farabi.engine.DataSourcePorts.ProviderRegistry;
import tech.kayys.farabi.engine.DataSourceRegistryService;
import tech.kayys.farabi.engine.QueryLifecycle;
import tech.kayys.farabi.jdbc.source.JdbcConnectionRegistry;
import tech.kayys.farabi.jdbc.source.JdbcQueryProvider;
import tech.kayys.farabi.jdbc.source.JdbcSourceProvider;
import tech.kayys.farabi.jdbc.source.SecretBackedJdbcConnectionFactory;
import tech.kayys.farabi.jdbc.source.WorkspaceScopedJdbcConnectionFactory;

/** Quarkus composition root for workspace-scoped JDBC and Dremio source providers. */
@ApplicationScoped
public class JdbcSourceRuntime {
    private final Instance<SecretResolver> secretResolvers;
    private final String allowedHosts;

    @Inject
    public JdbcSourceRuntime(
            Instance<SecretResolver> secretResolvers,
            @ConfigProperty(name = "farabi.sources.allowed-hosts", defaultValue = "") String allowedHosts) {
        this.secretResolvers = secretResolvers;
        this.allowedHosts = allowedHosts;
    }

    @Produces @Singleton
    ConnectionRegistry connectionRegistry(DataSource metadataDataSource) {
        return new JdbcConnectionRegistry(metadataDataSource);
    }

    private tech.kayys.farabi.engine.DataSourcePorts.SecretResolver secretResolver() {
        return reference -> {
            if (!secretResolvers.isResolvable()) {
                throw new IllegalStateException("no deployment SecretResolver is configured");
            }
            char[] value = secretResolvers.get().resolve(reference);
            if (value == null || value.length == 0) {
                if (value != null) java.util.Arrays.fill(value, '\0');
                throw new IllegalStateException("configured source secret could not be resolved");
            }
            return value;
        };
    }

    @Produces @Singleton
    JdbcSourceProvider jdbcSourceProvider() {
        var connections = new SecretBackedJdbcConnectionFactory(
                secretResolver(), new AllowlistedJdbcConnectionPolicy(allowedHosts),
                (url, properties) -> DriverManager.getConnection(url, properties));
        return new JdbcSourceProvider(connections);
    }

    @Produces @Singleton
    DremioSourceProvider dremioSourceProvider() {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        return new DremioSourceProvider(
                new DremioSourceFactory(http, new ObjectMapper(), secretResolver()));
    }

    @Produces @Singleton
    DremioDataSourcePortAdapter dremioDataSourcePort(DremioSourceProvider dremio) {
        return new DremioDataSourcePortAdapter(dremio);
    }

    @Produces @Singleton
    ProviderRegistry providerRegistry(JdbcSourceProvider jdbc, DremioDataSourcePortAdapter dremio) {
        return key -> {
            if (JdbcSourceProvider.PROVIDER_KEY.equals(key)) return Optional.of(jdbc);
            if ("dremio".equalsIgnoreCase(key)) return Optional.of(dremio);
            return Optional.empty();
        };
    }

    /** Deny by default until Numu-backed identity and authorization are installed. */
    @Produces @Singleton
    ConnectionAuthorizer connectionAuthorizer() {
        return (workspace, subject, source, action) -> {
            throw new SecurityException("data-source authorization adapter is not configured");
        };
    }

    @Produces @Singleton
    DataSourceRegistryService dataSourceRegistryService(
            ConnectionRegistry registry, ProviderRegistry providers, ConnectionAuthorizer authorizer) {
        return new DataSourceRegistryService(registry, providers, authorizer);
    }

    /** Query adapter is available as a lifecycle provider, but not yet exposed as a public query endpoint. */
    @Produces @Singleton
    QueryLifecycle.Provider jdbcQueryLifecycleProvider(
            ConnectionRegistry registry, ConnectionAuthorizer authorizer, JdbcSourceProvider jdbc) {
        var scoped = new WorkspaceScopedJdbcConnectionFactory(registry, authorizer, jdbc::openConnection);
        return new JdbcQueryProvider(scoped, 100_000, 1_000, 10_000, 128);
    }
}
