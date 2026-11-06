package tech.kayys.farabi.quarkus;

/**
 * Resolves an opaque secret reference through the deployment's secret manager.
 * Implementations must never log the resolved value.
 */
@FunctionalInterface
public interface SecretResolver {
    String resolve(String secretReference);
}
