package tech.kayys.farabi.adapter.dremio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import tech.kayys.farabi.engine.domain.QueryPlan;
import tech.kayys.farabi.engine.domain.QuerySubmission;
import tech.kayys.farabi.engine.port.QueryExecutor;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** Dremio REST v3 adapter; accepts only precompiled, authorized QueryPlan values. */
public final class DremioQueryExecutor implements QueryExecutor {
    private final HttpClient http;
    private final ObjectMapper json;
    private final URI sqlEndpoint;
    private final String token;

    public DremioQueryExecutor(HttpClient http, ObjectMapper json, URI baseUrl, String token) {
        this.http = Objects.requireNonNull(http);
        this.json = Objects.requireNonNull(json);
        Objects.requireNonNull(baseUrl);
        if (!"https".equalsIgnoreCase(baseUrl.getScheme()))
            throw new IllegalArgumentException("Dremio endpoint must use HTTPS");
        if (baseUrl.getHost() == null || baseUrl.getRawQuery() != null || baseUrl.getRawFragment() != null)
            throw new IllegalArgumentException("Invalid Dremio endpoint");
        if (token == null || token.isBlank()) throw new IllegalArgumentException("Dremio token required");
        this.token = token;
        this.sqlEndpoint = baseUrl.resolve("/api/v3/sql");
    }

    @Override public QuerySubmission submit(QueryPlan plan) {
        Objects.requireNonNull(plan);
        try {
            String body = json.writeValueAsString(Map.of("sql", plan.sql()));
            HttpRequest request = HttpRequest.newBuilder(sqlEndpoint)
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new IllegalStateException("Dremio query submission failed (HTTP " + response.statusCode() + ")");
            JsonNode payload = json.readTree(response.body());
            JsonNode id = payload.path("id");
            if (!id.isTextual() || id.asText().isBlank())
                throw new IllegalStateException("Dremio response did not contain a job id");
            return new QuerySubmission(id.asText());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Dremio submission interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("Dremio submission transport failed", e);
        }
    }
}
