package tech.kayys.farabi.engine;

import java.util.Optional;
import java.util.UUID;
import tech.kayys.farabi.engine.QueryContracts.*;

public final class QueryPorts {
    private QueryPorts() {}
    @FunctionalInterface public interface Authorizer { void authorize(Request request); }
    @FunctionalInterface public interface Compiler { Plan compile(Request request); }
    @FunctionalInterface public interface Executor { Submission submit(Plan plan); }
    public interface ExecutionRepository {
        void create(Execution execution);
        Optional<Execution> find(UUID id, String workspaceId);
        boolean transition(UUID id, String workspaceId, Status expected, Status next, String providerJobId);
    }
}
