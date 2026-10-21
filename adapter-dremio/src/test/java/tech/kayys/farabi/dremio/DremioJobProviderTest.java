package tech.kayys.farabi.dremio;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DremioJobProviderTest {
    @Test void requiresTlsAndCredentials() {
        assertThrows(IllegalArgumentException.class,()->new DremioJobProvider(
            HttpClient.newHttpClient(),new ObjectMapper(),URI.create("http://localhost:9047"),"token"));
        assertThrows(IllegalArgumentException.class,()->new DremioJobProvider(
            HttpClient.newHttpClient(),new ObjectMapper(),URI.create("https://example.com")," "));
    }
    @Test void validatesPageAndJobIdBeforeNetwork() {
        var provider=new DremioJobProvider(HttpClient.newHttpClient(),new ObjectMapper(),URI.create("https://example.com"),"token");
        assertThrows(IllegalArgumentException.class,()->provider.results("job",0,0));
        assertThrows(IllegalArgumentException.class,()->provider.results("job",-1,10));
        assertThrows(IllegalArgumentException.class,()->provider.status(" "));
    }
}
