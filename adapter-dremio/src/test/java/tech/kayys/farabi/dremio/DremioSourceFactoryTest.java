package tech.kayys.farabi.dremio;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.DataSourceContracts;
import static org.junit.jupiter.api.Assertions.*;

class DremioSourceFactoryTest {
    private static DataSourceContracts.ConnectionDefinition definition(
            DataSourceContracts.ProviderKind kind, DataSourceContracts.SecretReference credential) {
        return new DataSourceContracts.ConnectionDefinition("source-1", "workspace-a", "Warehouse",
                kind, kind == DataSourceContracts.ProviderKind.DREMIO ? "dremio" : "jdbc",
                URI.create("https://dremio.example"), null, null, Map.of(), credential, true, Instant.EPOCH);
    }

    @Test void composesCatalogExecutorAndLifecycleAndClearsResolvedSecretBuffer() {
        char[] secret = "test-token".toCharArray();
        var factory = new DremioSourceFactory(HttpClient.newHttpClient(), new ObjectMapper(), reference -> {
            assertEquals("vault://workspace-a/dremio", reference);
            return secret;
        });

        var source = factory.open(definition(DataSourceContracts.ProviderKind.DREMIO,
                new DataSourceContracts.SecretReference("vault://workspace-a/dremio")));

        assertEquals("source-1", source.descriptor().sourceId());
        assertInstanceOf(DremioCatalog.class, source.catalog());
        assertNotNull(source.executor());
        assertNotNull(source.lifecycle());
        assertTrue(Arrays.equals(new char[secret.length], secret));
    }

    @Test void rejectsMissingCredentialAndNonDremioProvider() {
        var factory = new DremioSourceFactory(HttpClient.newHttpClient(), new ObjectMapper(), ref -> "token".toCharArray());
        assertThrows(IllegalArgumentException.class, () -> factory.open(
                definition(DataSourceContracts.ProviderKind.DREMIO, null)));
        assertThrows(IllegalArgumentException.class, () -> factory.open(
                definition(DataSourceContracts.ProviderKind.JDBC, new DataSourceContracts.SecretReference("vault://db"))));
    }
}
