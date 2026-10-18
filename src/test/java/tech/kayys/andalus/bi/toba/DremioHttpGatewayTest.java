package tech.kayys.andalus.bi.toba;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class DremioHttpGatewayTest {
    private HttpServer server;

    private DremioHttpGateway gateway() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        return new DremioHttpGateway(HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                () -> "test-token", Duration.ofSeconds(3));
    }

    @AfterEach void stop() {
        if (server != null) server.stop(0);
    }

    @Test void submitsSqlAndFetchesResults() throws Exception {
        var gateway = gateway();
        var seenAuth = new AtomicReference<String>();
        server.createContext("/api/v3/sql", exchange -> {
            seenAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals("POST", exchange.getRequestMethod());
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(body.contains("SELECT 42"));
            byte[] result = "{\"id\":\"job_123\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, result.length);
            try (var out = exchange.getResponseBody()) { out.write(result); }
        });
        server.createContext("/api/v3/job/job_123/results", exchange -> {
            assertEquals("offset=0&limit=10", exchange.getRequestURI().getQuery());
            byte[] result = "{\"rows\":[{\"answer\":42}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, result.length);
            try (var out = exchange.getResponseBody()) { out.write(result); }
        });
        assertEquals("job_123", gateway.submitTrustedSql("SELECT 42"));
        assertEquals("_dremiotest-token", seenAuth.get());
        assertEquals(42, gateway.results("job_123", 0, 10).path("rows").get(0).path("answer").asInt());
    }

    @Test void rejectsUntrustedEndpointAndInvalidJobId() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new DremioHttpGateway(
                HttpClient.newHttpClient(), URI.create("http://remote.example"),
                () -> "token", Duration.ofSeconds(3)));
        var gateway = gateway();
        assertThrows(IllegalArgumentException.class, () -> gateway.status("../admin"));
        assertThrows(IllegalArgumentException.class, () -> gateway.results("job_123", 0, 1001));
    }

    @Test void doesNotExposeServerErrorBody() throws Exception {
        var gateway = gateway();
        server.createContext("/api/v3/sql", exchange -> {
            byte[] body = "sensitive internal details".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(403, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        var error = assertThrows(DremioHttpGateway.DremioGatewayException.class,
                () -> gateway.submitTrustedSql("SELECT 1"));
        assertEquals(403, error.status());
        assertFalse(error.getMessage().contains("sensitive"));
    }
}
