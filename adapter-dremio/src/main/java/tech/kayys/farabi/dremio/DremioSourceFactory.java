package tech.kayys.farabi.dremio;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.util.Arrays;
import java.util.Objects;
import tech.kayys.farabi.engine.DataSourceContracts;
import tech.kayys.farabi.engine.DataSourceContracts.ConnectionDefinition;
import tech.kayys.farabi.engine.DataSourceContracts.Descriptor;
import tech.kayys.farabi.engine.DataSourceContracts.ProviderKind;
import tech.kayys.farabi.engine.DataSourcePorts.SecretResolver;

/**
 * Composes endpoint-scoped Dremio REST adapters from a persisted source
 * definition. Secret material is resolved only while composing the clients;
 * the returned character buffer is cleared immediately afterwards.
 */
public final class DremioSourceFactory implements DremioSourceProvider.SourceFactory {
    private final HttpClient http;
    private final ObjectMapper json;
    private final SecretResolver secrets;

    public DremioSourceFactory(HttpClient http, ObjectMapper json, SecretResolver secrets) {
        this.http = Objects.requireNonNull(http, "http");
        this.json = Objects.requireNonNull(json, "json");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
    }

    @Override
    public DremioSourceProvider.Source open(ConnectionDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        if (definition.providerKind() != ProviderKind.DREMIO
                || !"dremio".equalsIgnoreCase(definition.providerKey())) {
            throw new IllegalArgumentException("connection definition is not configured for Dremio");
        }
        if (definition.credential() == null) {
            throw new IllegalArgumentException("Dremio source requires a secret reference");
        }
        char[] secret = Objects.requireNonNull(
                secrets.resolve(definition.credential().reference()), "secret resolver returned null");
        if (secret.length == 0) {
            Arrays.fill(secret, '\0');
            throw new IllegalArgumentException("Dremio source secret is empty");
        }
        try {
            String token = new String(secret);
            Descriptor descriptor = new Descriptor(
                    definition.sourceId(), "dremio", definition.displayName(), "v3");
            return new DremioSourceProvider.Source(
                    descriptor,
                    new DremioCatalog(http, json, definition.endpoint(), token),
                    new DremioExecutor(http, json, definition.endpoint(), token),
                    new DremioJobProvider(http, json, definition.endpoint(), token));
        } finally {
            Arrays.fill(secret, '\0');
        }
    }
}
