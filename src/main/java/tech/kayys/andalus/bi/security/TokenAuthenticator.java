package tech.kayys.andalus.bi.security;

import java.util.Optional;

/**
 * Pluggable authentication port. Implementations must cryptographically verify
 * token signatures, issuer, audience, expiry, and workspace membership against
 * a trusted identity provider. Never parse unverified JWT claims as identity.
 */
@FunctionalInterface
public interface TokenAuthenticator {
    Optional<AccessContext> authenticate(String bearerToken);
}
