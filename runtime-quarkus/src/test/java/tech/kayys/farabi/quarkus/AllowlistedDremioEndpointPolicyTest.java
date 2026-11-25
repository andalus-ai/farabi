package tech.kayys.farabi.quarkus;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import org.junit.jupiter.api.Test;

class AllowlistedDremioEndpointPolicyTest {
    @Test
    void allowsOnlyExplicitHttpsHosts() {
        var policy = new AllowlistedDremioEndpointPolicy("dremio.internal.example, analytics.example");
        assertDoesNotThrow(() -> policy.validate(URI.create("https://dremio.internal.example")));
        assertThrows(SecurityException.class,
                () -> policy.validate(URI.create("https://other.internal.example")));
        assertThrows(SecurityException.class,
                () -> policy.validate(URI.create("http://dremio.internal.example")));
    }

    @Test
    void rejectsEmptyAllowlistAndCredentialBearingEndpoints() {
        var policy = new AllowlistedDremioEndpointPolicy("");
        assertThrows(SecurityException.class,
                () -> policy.validate(URI.create("https://dremio.internal.example")));
        var configured = new AllowlistedDremioEndpointPolicy("dremio.internal.example");
        assertThrows(SecurityException.class,
                () -> configured.validate(URI.create("https://user:secret@dremio.internal.example")));
        assertThrows(SecurityException.class,
                () -> configured.validate(URI.create("https://dremio.internal.example?token=secret")));
    }
}
