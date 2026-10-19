package tech.kayys.andalus.bi.toba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Dremio REST v3 transport for a trusted, already-authorized server-side query.
 *
 * NOT an HTTP endpoint and NOT a permission checker. Call only after actor-aware
 * semantic validation and source-side authorization. No raw user/agent SQL.
 * Uses a server-provisioned Dremio access token, not a caller-supplied token.
 */
public final class DremioHttpGateway {
    private static final Pattern JOB_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    private final HttpClient client;
    private final URI baseUri;
    private final Supplier<String> accessToken;
    private final ObjectMapper json = new ObjectMapper();
    private final Duration timeout;

    public DremioHttpGateway(HttpClient client, URI baseUri,
                             Supplier<String> accessToken, Duration timeout) {
        this.client = Objects.requireNonNull(client);
        this.baseUri = Objects.requireNonNull(baseUri);
        this.accessToken = Objects.requireNonNull(accessToken);
        this.timeout = Objects.requireNonNull(timeout);
        if (!"https".equalsIgnoreCase(baseUri.getScheme())
                && !("http".equalsIgnoreCase(baseUri.getScheme())
                     && ("localhost".equalsIgnoreCase(baseUri.getHost())
                         || "127.0.0.1".equals(baseUri.getHost())))) {
            throw new IllegalArgumentException("Dremio endpoint must use HTTPS (HTTP permitted only for localhost)");
        }
        if (baseUri.getUserInfo() != null || baseUri.getQuery() != null || baseUri.getFragment() != null
                || !("/".equals(baseUri.getPath()) || baseUri.getPath().isEmpty())) {
            throw new IllegalArgumentException("Dremio base URI must be an origin only");
        }
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("timeout must be positive and <= 5 minutes");
        }
    }

    /** Dremio REST v3 POST /api/v3/sql returns an id for asynchronous job polling. */
    public String submitTrustedSql(String sql) {
        if (sql == null || sql.isBlank() || sql.length() > 100_000) {
            throw new IllegalArgumentException("validated SQL required");
        }
        try {
            String payload = json.createObjectNode().put("sql", sql).toString();
            JsonNode response = request("POST", "/api/v3/sql", payload);
            String id = response.path("id").asText("");
            return requireJobId(id);
        } catch (IOException e) {
            throw new IllegalStateException("unable to encode Dremio request", e);
        }
    }

    public JsonNode status(String jobId) {
        return request("GET", "/api/v3/job/" + requireJobId(jobId), null);
    }

    public JsonNode results(String jobId, int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("invalid result page");
        }
        return request("GET", "/api/v3/job/" + requireJobId(jobId)
                + "/results?offset=" + offset + "&limit=" + limit, null);
    }

    private JsonNode request(String method, String path, String payload) {
        String token = accessToken.get();
        if (token == null || token.isBlank() || token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0) {
            throw new IllegalStateException("Dremio access token unavailable");
        }
        URI uri = baseUri.resolve(path);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Authorization", "_dremio" + token)
                .header("Accept", "application/json");
        if ("POST".equals(method)) {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload));
        } else {
            builder.GET();
        }
        try {
            HttpResponse<byte[]> response = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.body().length > MAX_RESPONSE_BYTES) {
                throw new IllegalStateException("Dremio response exceeds size limit");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new DremioGatewayException(response.statusCode());
            }
            return json.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Dremio request interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("Dremio request failed", e);
        }
    }

    private static String requireJobId(String jobId) {
        if (jobId == null || !JOB_ID.matcher(jobId).matches()) {
            throw new IllegalArgumentException("invalid Dremio job id");
        }
        return jobId;
    }

    public static final class DremioGatewayException extends RuntimeException {
        private final int status;
        public DremioGatewayException(int status) {
            super("Dremio returned HTTP " + status);
            this.status = status;
        }
        public int status() { return status; }
    }
}
