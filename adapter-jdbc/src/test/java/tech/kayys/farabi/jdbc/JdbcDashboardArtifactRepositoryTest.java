package tech.kayys.farabi.jdbc;

import java.util.List;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.DashboardArtifacts.Chart;
import static org.junit.jupiter.api.Assertions.*;

class JdbcDashboardArtifactRepositoryTest {
    @Test void chartEncodingRoundTripsSpecialCharacters() {
        var charts=List.of(new Chart("a:b","line|bar","data.set","rev:enue"));
        assertEquals(charts,JdbcDashboardArtifactRepository.decode(JdbcDashboardArtifactRepository.encode(charts)));
    }
    @Test void rejectsMalformedEncoding() {
        assertThrows(IllegalArgumentException.class,()->JdbcDashboardArtifactRepository.decode("1:3:abc"));
    }
}
