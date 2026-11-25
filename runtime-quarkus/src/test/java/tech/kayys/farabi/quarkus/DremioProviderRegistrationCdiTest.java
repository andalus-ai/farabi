package tech.kayys.farabi.quarkus;

import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import io.quarkus.test.junit.QuarkusTest;
import tech.kayys.farabi.engine.DataSourcePorts.ProviderRegistry;
import tech.kayys.farabi.jdbc.source.JdbcSourceProvider;

/** Verifies both built-in source adapters are present in the real CDI composition root. */
@QuarkusTest
class DremioProviderRegistrationCdiTest {
    @Inject
    ProviderRegistry providers;

    @Test
    void registersDremioAndKeepsGenericJdbcAvailable() {
        assertTrue(providers.find("dremio").isPresent());
        assertTrue(providers.find(JdbcSourceProvider.PROVIDER_KEY).isPresent());
        assertTrue(providers.find("unknown-provider").isEmpty());
    }
}
