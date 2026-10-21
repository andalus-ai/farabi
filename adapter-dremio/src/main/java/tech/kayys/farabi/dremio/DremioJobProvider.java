package tech.kayys.farabi.dremio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import tech.kayys.farabi.engine.QueryContracts.Status;
import tech.kayys.farabi.engine.QueryLifecycle.*;

/**
 * Dremio v3 job API lifecycle adapter.
 * Remote states are mapped conservatively; unknown states fail closed.
 */
public final class DremioJobProvider implements Provider {
    private final HttpClient http;
    private final ObjectMapper json;
    private final URI base;
    private final String token;
    public DremioJobProvider(HttpClient http,ObjectMapper json,URI base,String token) {
        this.http=Objects.requireNonNull(http);this.json=Objects.requireNonNull(json);
        this.base=Objects.requireNonNull(base);
        if(!"https".equalsIgnoreCase(base.getScheme())||base.getHost()==null||base.getRawQuery()!=null||base.getRawFragment()!=null)
            throw new IllegalArgumentException("HTTPS Dremio URL required");
        if(token==null||token.isBlank()) throw new IllegalArgumentException("Dremio token required");
        this.token=token;
    }
    @Override public RemoteState status(String id) {
        JsonNode body=call("GET",path(id),null);
        String state=body.path("jobState").asText("");
        if(state.isBlank()) state=body.path("state").asText("");
        Status mapped=switch(state.toUpperCase(Locale.ROOT)) {
            case "RUNNING","STARTING","PENDING","QUEUED","ENQUEUED","PLANNING","ENGINE_START","EXECUTION_PLANNING" -> Status.RUNNING;
            case "COMPLETED","SUCCEEDED" -> Status.SUCCEEDED;
            case "FAILED" -> Status.FAILED;
            case "CANCELED","CANCELLED" -> Status.CANCELLED;
            default -> throw new IllegalStateException("unrecognized Dremio job state: "+state);
        };
        return new RemoteState(mapped,body.path("errorMessage").asText(null));
    }
    @Override public ResultPage results(String id,int offset,int limit) {
        if(offset<0||limit<1||limit>1000) throw new IllegalArgumentException("invalid page");
        JsonNode body=call("GET",path(id)+"/results?offset="+offset+"&limit="+limit,null);
        JsonNode rows=body.path("rows");
        if(!rows.isArray()) throw new IllegalStateException("Dremio results missing rows");
        List<String> columns=new ArrayList<>();
        JsonNode schema=body.path("schema");
        if(schema.isArray()) for(JsonNode column:schema) {
            String name=column.path("name").asText("");
            if(!name.isBlank()) columns.add(name);
        }
        List<Map<String,Object>> data=new ArrayList<>();
        for(JsonNode row:rows) {
            if(!row.isObject()) throw new IllegalStateException("invalid Dremio row");
            @SuppressWarnings("unchecked") Map<String,Object> value=json.convertValue(row,Map.class);
            data.add(value);
        }
        // Dremio may return a rowCount field. A full page alone does not prove more rows exist.
        long total=body.path("rowCount").asLong(-1);
        boolean hasMore=total>=0 && (long)offset+data.size()<total;
        return new ResultPage(columns,data,offset,hasMore);
    }
    @Override public void cancel(String id) {
        call("POST",path(id)+"/cancel","");
    }
    private String path(String id) {
        if(id==null||id.isBlank()) throw new IllegalArgumentException("job id required");
        return "/api/v3/job/"+URLEncoder.encode(id,StandardCharsets.UTF_8).replace("+","%20");
    }
    private JsonNode call(String method,String path,String body) {
        URI uri=base.resolve(path);
        HttpRequest.Builder builder=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
            .header("Authorization","Bearer "+token).header("Accept","application/json");
        HttpRequest request="POST".equals(method)
            ?builder.POST(HttpRequest.BodyPublishers.ofString(body)).build()
            :builder.GET().build();
        try {
            HttpResponse<String> response=http.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()<200||response.statusCode()>=300)
                throw new IllegalStateException("Dremio job API HTTP "+response.statusCode());
            if(response.body()==null||response.body().isBlank()) return json.createObjectNode();
            return json.readTree(response.body());
        } catch(InterruptedException ex) {
            Thread.currentThread().interrupt();throw new IllegalStateException("Dremio job request interrupted",ex);
        } catch(IOException ex) { throw new IllegalStateException("Dremio job transport failure",ex); }
    }
}
