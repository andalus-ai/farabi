package tech.kayys.farabi.quarkus;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.enterprise.inject.Instance;
import tech.kayys.farabi.jdbc.datasource.JdbcDataSourceRegistry;

/** Quarkus lifecycle boundary for runtime-owned customer JDBC pools. */
@ApplicationScoped
public class JdbcSourceRuntime {
    private final JdbcConnectionPoolManager pools;

    @Inject
    public JdbcSourceRuntime(JdbcSourcesConfig config, Instance<SecretResolver> secretResolvers) {
        SecretResolver resolver = secretResolvers.isResolvable() ? secretResolvers.get() : null;
        this.pools = JdbcConnectionPoolManager.from(config, resolver);
    }

    @Produces
    @Singleton
    JdbcDataSourceRegistry registry() {
        return pools.registry();
    }

    @PreDestroy
    void shutdown() {
        pools.close();
    }
}
