package tech.kayys.farabi.quarkus;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.enterprise.inject.Instance;
import tech.kayys.farabi.jdbc.datasource.JdbcDataSourceRegistry;
import tech.kayys.farabi.jdbc.datasource.JdbcCatalogProvider;
import tech.kayys.farabi.jdbc.datasource.JdbcCapabilitiesProvider;
import tech.kayys.farabi.jdbc.datasource.JdbcQueryExecutor;
import tech.kayys.farabi.engine.DataSourcePorts.CatalogProvider;
import tech.kayys.farabi.engine.DataSourcePorts.CapabilitiesProvider;
import tech.kayys.farabi.engine.DataSourcePorts.QueryExecutor;

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

    @Produces
    @Singleton
    CatalogProvider catalogProvider(JdbcDataSourceRegistry registry) {
        return new JdbcCatalogProvider(registry);
    }

    @Produces
    @Singleton
    CapabilitiesProvider capabilitiesProvider(JdbcDataSourceRegistry registry) {
        return new JdbcCapabilitiesProvider(registry);
    }

    @Produces
    @Singleton
    QueryExecutor queryExecutor(JdbcDataSourceRegistry registry) {
        return new JdbcQueryExecutor(registry);
    }

    @PreDestroy
    void shutdown() {
        pools.close();
    }
}
