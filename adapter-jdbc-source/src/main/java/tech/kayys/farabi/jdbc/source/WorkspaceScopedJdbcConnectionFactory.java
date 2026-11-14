package tech.kayys.farabi.jdbc.source;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import tech.kayys.farabi.engine.DataSourceContracts.ConnectionDefinition;
import tech.kayys.farabi.engine.DataSourceContracts.ProviderKind;
import tech.kayys.farabi.engine.DataSourcePorts.Action;
import tech.kayys.farabi.engine.DataSourcePorts.ConnectionAuthorizer;
import tech.kayys.farabi.engine.DataSourcePorts.ConnectionRegistry;

/**
 * Enforces workspace-scoped source resolution and QUERY authorization before a JDBC
 * connection can be opened. The subject/workspace values must originate from the
 * authenticated server-side execution context carried by the compiled Plan.
 */
public final class WorkspaceScopedJdbcConnectionFactory implements JdbcQueryProvider.ConnectionFactory {
    private final ConnectionRegistry registry;
    private final ConnectionAuthorizer authorizer;
    private final JdbcSourceProvider.ConnectionFactory connections;

    public WorkspaceScopedJdbcConnectionFactory(ConnectionRegistry registry,
                                                 ConnectionAuthorizer authorizer,
                                                 JdbcSourceProvider.ConnectionFactory connections) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    @Override
    public Connection open(String sourceId, String workspaceId, String subjectId) throws SQLException {
        tech.kayys.farabi.engine.QueryContracts.required(sourceId, "sourceId");
        tech.kayys.farabi.engine.QueryContracts.required(workspaceId, "workspaceId");
        tech.kayys.farabi.engine.QueryContracts.required(subjectId, "subjectId");

        // Authorize before lookup so a caller cannot use source existence as an oracle.
        authorizer.authorize(workspaceId, subjectId, sourceId, Action.QUERY);
        ConnectionDefinition definition = registry.find(sourceId, workspaceId)
                .orElseThrow(() -> new SecurityException("data source is not available in the authorized workspace"));
        if (!sourceId.equals(definition.sourceId()) || !workspaceId.equals(definition.workspaceId())) {
            throw new SecurityException("connection registry returned a source outside the requested scope");
        }
        if (definition.providerKind() != ProviderKind.JDBC
                || !JdbcSourceProvider.PROVIDER_KEY.equals(definition.providerKey())) {
            throw new IllegalArgumentException("source is not configured for the generic JDBC provider");
        }
        if (!definition.readOnly()) {
            throw new SecurityException("analytical JDBC execution requires a read-only source definition");
        }
        return connections.open(definition);
    }
}
