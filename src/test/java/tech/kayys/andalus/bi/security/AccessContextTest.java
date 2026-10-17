package tech.kayys.andalus.bi.security;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class AccessContextTest {
    @Test void permitsOnlyGrantedWorkspaces() {
        var actor = new AccessContext("user-1", Set.of("workspace-a"));
        assertDoesNotThrow(() -> actor.requireWorkspace("workspace-a"));
        assertThrows(AccessDeniedException.class, () -> actor.requireWorkspace("workspace-b"));
        assertThrows(AccessDeniedException.class, () -> actor.requireWorkspace(null));
    }

    @Test void rejectsMissingIdentity() {
        assertThrows(IllegalArgumentException.class, () -> new AccessContext("", Set.of("workspace-a")));
        assertThrows(NullPointerException.class, () -> new AccessContext("user-1", null));
    }
}
