package tech.kayys.farabi.dremio;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DremioExecutorTest {
    @Test void rejectsInsecureEndpoint() {
        assertThrows(IllegalArgumentException.class,()->new DremioExecutor(
            HttpClient.newHttpClient(),new ObjectMapper(),URI.create("http://example.com"),"token"));
    }
    @Test void refusesPlansWithParametersInsteadOfSilentlyDroppingThem() {
        var executor=new DremioExecutor(HttpClient.newHttpClient(),new ObjectMapper(),
            URI.create("https://example.com"),"token");
        var plan=new tech.kayys.farabi.engine.QueryContracts.Plan("source", "select ?",
            java.util.List.of(new tech.kayys.farabi.engine.QueryContracts.Parameter(1,"secret",java.sql.Types.VARCHAR)));
        assertThrows(UnsupportedOperationException.class,()->executor.submit(plan));
    }
    @Test void rejectsMissingToken() {
        assertThrows(IllegalArgumentException.class,()->new DremioExecutor(
            HttpClient.newHttpClient(),new ObjectMapper(),URI.create("https://example.com")," "));
    }
}
