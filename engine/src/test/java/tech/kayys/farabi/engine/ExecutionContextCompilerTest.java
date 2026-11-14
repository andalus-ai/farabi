package tech.kayys.farabi.engine;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.QueryContracts.Plan;
import tech.kayys.farabi.engine.QueryContracts.Request;

class ExecutionContextCompilerTest {
    @Test
    void copiesAuthenticatedRequestScopeIntoCompiledPlan() {
        var request = new Request("workspace-a", "analyst", "sales", "orders:revenue", Map.of());
        var compiler = new ExecutionContextCompiler(ignored ->
                new Plan("sales", "SELECT SUM(amount) FROM orders", List.of()));

        Plan plan = compiler.compile(request);

        assertEquals("workspace-a", plan.workspaceId());
        assertEquals("analyst", plan.subjectId());
        assertEquals("sales", plan.sourceId());
    }

    @Test
    void overwritesContextReturnedByDelegate() {
        var request = new Request("workspace-a", "analyst", "sales", "orders:revenue", Map.of());
        var compiler = new ExecutionContextCompiler(ignored ->
                new Plan("sales", "SELECT 1", List.of(), "attacker-workspace", "attacker"));

        Plan plan = compiler.compile(request);

        assertEquals("workspace-a", plan.workspaceId());
        assertEquals("analyst", plan.subjectId());
    }

    @Test
    void rejectsCompilerSourceMismatch() {
        var request = new Request("workspace-a", "analyst", "sales", "orders:revenue", Map.of());
        var compiler = new ExecutionContextCompiler(ignored ->
                new Plan("other-source", "SELECT 1", List.of()));

        assertThrows(IllegalStateException.class, () -> compiler.compile(request));
    }
}
