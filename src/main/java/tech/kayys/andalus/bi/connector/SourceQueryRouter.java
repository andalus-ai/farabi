package tech.kayys.andalus.bi.connector;

import tech.kayys.andalus.bi.query.QueryExecutor;
import tech.kayys.andalus.bi.query.QueryPlan;
import tech.kayys.andalus.bi.security.AccessContext;

import java.time.Duration;
import java.util.Objects;

/**
 * Routes precompiled, trusted plans to workspace-scoped sources.
 * The caller must use an actor-authorized semantic catalog and compiler.
 */
public final class SourceQueryRouter {
    private final SourceRegistry sources;
    private final ConnectorRegistry connectors;

    public SourceQueryRouter(SourceRegistry sources, ConnectorRegistry connectors) {
        this.sources = Objects.requireNonNull(sources);
        this.connectors = Objects.requireNonNull(connectors);
    }

    public QueryExecutor.QueryResult execute(AccessContext actor, String workspaceId,
                                             QueryPlan plan, Duration timeout) {
        Objects.requireNonNull(actor, "actor").requireWorkspace(workspaceId);
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(timeout, "timeout");
        SourceDescriptor source = sources.require(workspaceId, plan.sourceId());
        SourceConnector connector = connectors.require(source.connectorType());
        if (plan.limit() > connector.capabilities().maxResultRows()) {
            throw new IllegalArgumentException("result limit exceeds connector policy");
        }
        if (!connector.capabilities().preparedParameters()) {
            throw new IllegalArgumentException("connector does not support safe parameter binding");
        }
        if (!connector.capabilities().queryTimeout()) {
            throw new IllegalArgumentException("connector does not support query timeouts");
        }
        return connector.execute(source, plan, timeout);
    }
}
