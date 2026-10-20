package tech.kayys.farabi.dremio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import tech.kayys.farabi.engine.QueryContracts.*;
import tech.kayys.farabi.engine.QueryPorts.Executor;

/** Only use through a governed engine use case; does not authorize SQL. */
public final class DremioExecutor implements Executor {
    private final HttpClient http; private final ObjectMapper mapper; private final URI endpoint; private final String token;
    public DremioExecutor(HttpClient http,ObjectMapper mapper,URI baseUrl,String token) {
        this.http=Objects.requireNonNull(http);this.mapper=Objects.requireNonNull(mapper);Objects.requireNonNull(baseUrl);
        if(!"https".equalsIgnoreCase(baseUrl.getScheme())||baseUrl.getHost()==null||baseUrl.getRawQuery()!=null||baseUrl.getRawFragment()!=null)
            throw new IllegalArgumentException("Dremio endpoint must be a valid HTTPS base URL");
        if(token==null||token.isBlank()) throw new IllegalArgumentException("Dremio token required");
        this.endpoint=baseUrl.resolve("/api/v3/sql");this.token=token;
    }
    @Override public Submission submit(Plan plan) {
        Objects.requireNonNull(plan);
        try {
            HttpRequest request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(30))
                .header("Authorization","Bearer "+token).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("sql",plan.sql())))).build();
            HttpResponse<String> response=http.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()<200||response.statusCode()>=300) throw new IllegalStateException("Dremio HTTP "+response.statusCode());
            JsonNode id=mapper.readTree(response.body()).path("id");
            if(!id.isTextual()||id.asText().isBlank()) throw new IllegalStateException("Dremio response missing job id");
            return new Submission(id.asText());
        } catch(InterruptedException ex) {
            Thread.currentThread().interrupt();throw new IllegalStateException("Dremio submission interrupted",ex);
        } catch(IOException ex) { throw new IllegalStateException("Dremio submission transport failure",ex); }
    }
}
