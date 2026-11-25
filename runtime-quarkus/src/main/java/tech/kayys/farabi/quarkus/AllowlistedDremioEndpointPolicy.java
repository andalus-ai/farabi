package tech.kayys.farabi.quarkus;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Exact-host outbound allowlist for customer-configured Dremio HTTPS endpoints. */
final class AllowlistedDremioEndpointPolicy {
    private final Set<String> allowedHosts;

    AllowlistedDremioEndpointPolicy(String configuredHosts) {
        this.allowedHosts = Arrays.stream(configuredHosts == null ? new String[0] : configuredHosts.split(","))
                .map(String::trim)
                .filter(host -> !host.isEmpty())
                .map(host -> host.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    void validate(URI endpoint) {
        if (endpoint == null || !"https".equalsIgnoreCase(endpoint.getScheme())
                || endpoint.getHost() == null || endpoint.getRawUserInfo() != null
                || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null) {
            throw new SecurityException("Dremio endpoint must be a credential-free HTTPS URL");
        }
        String host = endpoint.getHost().toLowerCase(Locale.ROOT);
        if (!allowedHosts.contains(host)) {
            throw new SecurityException("Dremio endpoint host is not allowlisted");
        }
    }
}
