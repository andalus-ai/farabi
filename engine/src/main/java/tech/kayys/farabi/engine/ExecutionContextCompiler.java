package tech.kayys.farabi.engine;

import java.util.Objects;
import tech.kayys.farabi.engine.QueryContracts.Plan;
import tech.kayys.farabi.engine.QueryContracts.Request;
import tech.kayys.farabi.engine.QueryPorts.Compiler;

/**
 * Decorates a semantic compiler with the authenticated execution scope.
 *
 * <p>The runtime must construct {@link Request} using workspace and subject values
 * obtained from its authenticated server-side identity. Request bodies must never
 * be allowed to supply or override those fields. This decorator copies that trusted
 * scope into the compiled plan so adapters can enforce workspace-scoped access.</p>
 */
public final class ExecutionContextCompiler implements Compiler {
    private final Compiler delegate;

    public ExecutionContextCompiler(Compiler delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public Plan compile(Request request) {
        Objects.requireNonNull(request, "request");
        Plan plan = Objects.requireNonNull(delegate.compile(request), "compiler returned null");
        if (!request.sourceId().equals(plan.sourceId())) {
            throw new IllegalStateException("compiled plan source does not match authorized request");
        }
        // Never trust context returned by a compiler implementation; overwrite it
        // with the scope supplied by the authenticated application boundary.
        return new Plan(plan.sourceId(), plan.sql(), plan.parameters(),
                request.workspaceId(), request.subjectId());
    }
}
