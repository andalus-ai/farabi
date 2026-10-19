package tech.kayys.andalus.bi.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import tech.kayys.andalus.bi.application.AuthorizedDashboardService;
import tech.kayys.andalus.bi.artifact.WidgetSpec;
import tech.kayys.andalus.bi.persistence.DashboardConflictException;
import tech.kayys.andalus.bi.security.AccessContext;
import tech.kayys.andalus.bi.security.AccessDeniedException;
import tech.kayys.andalus.bi.security.TokenAuthenticator;
import tech.kayys.andalus.bi.semantic.SemanticMetric;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Minimal HTTP adapter registered at /api/v1/workspaces/.
 *
 * Routes:
 * POST /api/v1/workspaces/{workspace}/dashboards
 * GET  /api/v1/workspaces/{workspace}/dashboards/{uuid}
 * PUT  /api/v1/workspaces/{workspace}/dashboards/{uuid} (requires expectedVersion)
 * GET  /api/v1/workspaces/{workspace}/dashboards/{uuid}/versions
 *
 * A trusted TokenAuthenticator and workspace-scoped semantic metric resolver
 * must be injected. No permissive/default authenticator is provided.
 */
public final class DashboardHttpHandler implements HttpHandler {
    private static final int MAX_BODY_BYTES = 1024 * 1024;
    private final AuthorizedDashboardService dashboards;
    private final TokenAuthenticator authenticator;
    private final Function<String, Collection<SemanticMetric>> metricResolver;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    public record DashboardWriteRequest(String title, String description, Integer expectedVersion,
                                        List<WidgetSpec> widgets) {}

    public DashboardHttpHandler(AuthorizedDashboardService dashboards,
                                TokenAuthenticator authenticator,
                                Function<String, Collection<SemanticMetric>> metricResolver) {
        this.dashboards = Objects.requireNonNull(dashboards);
        this.authenticator = Objects.requireNonNull(authenticator);
        this.metricResolver = Objects.requireNonNull(metricResolver);
    }

    @Override public void handle(HttpExchange exchange) throws IOException {
        try {
            var actor = authenticate(exchange);
            if (actor.isEmpty()) { respond(exchange, 401, "{\"error\":\"unauthorized\"}"); return; }

            String[] parts = exchange.getRequestURI().getPath().split("/");
            if (parts.length < 6 || !parts[1].equals("api") || !parts[2].equals("v1")
                    || !parts[3].equals("workspaces") || !parts[5].equals("dashboards")
                    || parts[4].isBlank()) {
                respond(exchange, 404, "{\"error\":\"not_found\"}"); return;
            }

            String workspaceId = parts[4];
            actor.get().requireWorkspace(workspaceId);
            String method = exchange.getRequestMethod();

            if (parts.length == 6 && method.equals("POST")) {
                var request = readBody(exchange);
                var created = dashboards.create(actor.get(), workspaceId, request.title(),
                        request.description(), request.widgets(), metricResolver.apply(workspaceId));
                respond(exchange, 201, json.writeValueAsString(created)); return;
            }
            if (parts.length >= 7) {
                UUID dashboardId = UUID.fromString(parts[6]);
                if (parts.length == 7 && method.equals("GET")) {
                    var dashboard = dashboards.latest(actor.get(), workspaceId, dashboardId);
                    if (dashboard.isEmpty()) respond(exchange, 404, "{\"error\":\"not_found\"}");
                    else respond(exchange, 200, json.writeValueAsString(dashboard.get()));
                    return;
                }
                if (parts.length == 7 && method.equals("PUT")) {
                    var request = readBody(exchange);
                    if (request.expectedVersion() == null) throw new IllegalArgumentException("expectedVersion required");
                    var updated = dashboards.update(actor.get(), workspaceId, dashboardId,
                            request.expectedVersion(), request.title(), request.description(),
                            request.widgets(), metricResolver.apply(workspaceId));
                    respond(exchange, 200, json.writeValueAsString(updated)); return;
                }
                if (parts.length == 8 && parts[7].equals("versions") && method.equals("GET")) {
                    respond(exchange, 200, json.writeValueAsString(dashboards.versions(actor.get(), workspaceId, dashboardId)));
                    return;
                }
            }
            respond(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        } catch (AccessDeniedException denied) {
            respond(exchange, 403, "{\"error\":\"forbidden\"}");
        } catch (DashboardConflictException conflict) {
            respond(exchange, 409, "{\"error\":\"version_conflict\"}");
        } catch (IllegalArgumentException badRequest) {
            respond(exchange, 400, "{\"error\":\"bad_request\"}");
        } catch (Exception unexpected) {
            respond(exchange, 500, "{\"error\":\"internal_error\"}");
        } finally {
            exchange.close();
        }
    }

    private Optional<AccessContext> authenticate(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ") || header.length() <= 7) return Optional.empty();
        return authenticator.authenticate(header.substring(7));
    }

    private DashboardWriteRequest readBody(HttpExchange exchange) throws IOException {
        String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
        if (contentLength != null) {
            long length = Long.parseLong(contentLength);
            if (length < 0 || length > MAX_BODY_BYTES) throw new IllegalArgumentException("request too large");
        }
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) throw new IllegalArgumentException("request too large");
        var request = json.readValue(bytes, DashboardWriteRequest.class);
        if (request == null) throw new IllegalArgumentException("request required");
        return request;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
    }
}
