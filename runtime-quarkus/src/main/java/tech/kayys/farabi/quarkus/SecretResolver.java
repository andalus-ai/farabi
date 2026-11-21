package tech.kayys.farabi.quarkus;

/**
 * Resolves an opaque secret reference through the deployment's secret manager.
 * Implementations must never log the resolved value and should return a fresh
 * mutable character array; the JDBC adapter wipes that array after connection open.
 */
@FunctionalInterface
public interface SecretResolver {
    char[] resolve(String secretReference);
}
