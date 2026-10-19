package tech.kayys.farabi.adapter.dremio;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DremioQueryExecutorTest {
    @Test void rejectsInsecureEndpoint() {
        assertThrows(IllegalArgumentException.class,
            () -> new DremioQueryExecutor(HttpClient.newHttpClient(), new ObjectMapper(),
                URI.create("http://localhost:9047"), "token"));
    }
    @Test void rejectsMissingToken() {
        assertThrows(IllegalArgumentException.class,
            () -> new DremioQueryExecutor(HttpClient.newHttpClient(), new ObjectMapper(),
                URI.create("https://dremio.example"), ""));
    }
}
