package tech.kayys.farabi.quarkus;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.List;
import java.util.Map;

/** Deployment configuration for named customer analytical JDBC sources. */
@ConfigMapping(prefix = "farabi.sources")
public interface JdbcSourcesConfig {
    /** Exact DNS names permitted for outbound JDBC connections. */
    @WithDefault("")
    List<String> allowedHosts();

    /** Map keys are stable Farabi source IDs. */
    Map<String, JdbcSourceConfig> jdbc();

    interface JdbcSourceConfig {
        String displayName();
        String url();
        String username();
        String passwordSecretRef();
        @WithDefault("true") boolean readOnly();
        @WithDefault("true") boolean tlsRequired();
        @WithDefault("10") int maximumPoolSize();
        @WithDefault("0") int minimumIdle();
        @WithDefault("30000") long connectionTimeoutMs();
    }
}
