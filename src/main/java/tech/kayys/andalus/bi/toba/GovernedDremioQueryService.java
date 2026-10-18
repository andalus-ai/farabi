package tech.kayys.andalus.bi.toba;

import tech.kayys.andalus.bi.query.SemanticCatalog;
import tech.kayys.andalus.bi.query.SemanticQuery;
import tech.kayys.andalus.bi.query.SemanticSqlCompiler;
import tech.kayys.andalus.bi.security.AccessContext;
import tech.kayys.andalus.bi.security.AccessDeniedException;
import java.util.Objects;

/** Restricted actor-authorized semantic query submission to Dremio. */
public final class GovernedDremioQueryService {
    @FunctionalInterface
    public interface CatalogResolver {
        SemanticCatalog resolve(AccessContext actor, String workspaceId);
    }
    @FunctionalInterface
    public interface DatasetPolicy {
        boolean permits(AccessContext actor, String workspaceId, String sourceId);
    }
    private final CatalogResolver catalogs;
    private final DatasetPolicy policy;
    private final SemanticSqlCompiler compiler;
    private final DremioHttpGateway gateway;

    public GovernedDremioQueryService(CatalogResolver catalogs, DatasetPolicy policy,
                                      SemanticSqlCompiler compiler, DremioHttpGateway gateway) {
        this.catalogs = Objects.requireNonNull(catalogs);
        this.policy = Objects.requireNonNull(policy);
        this.compiler = Objects.requireNonNull(compiler);
        this.gateway = Objects.requireNonNull(gateway);
    }

    public String submit(AccessContext actor, String workspaceId, SemanticQuery query) {
        Objects.requireNonNull(actor).requireWorkspace(workspaceId);
        Objects.requireNonNull(query);
        if (!query.filters().isEmpty()) {
            throw new UnsupportedOperationException("Filtered Dremio queries require a safe dialect");
        }
        if (!policy.permits(actor, workspaceId, query.sourceId())) {
            throw new AccessDeniedException("dataset access denied");
        }
        var catalog = Objects.requireNonNull(catalogs.resolve(actor, workspaceId));
        var plan = compiler.compile(query, catalog);
        if (plan.parameters().size() != 1 || !(plan.parameters().getFirst() instanceof Integer limit)
                || limit != query.limit() || !plan.sql().endsWith(" LIMIT ?")) {
            throw new IllegalStateException("unsupported query plan");
        }
        String sql = plan.sql().substring(0, plan.sql().length() - 1) + limit;
        return gateway.submitTrustedSql(sql);
    }
}
