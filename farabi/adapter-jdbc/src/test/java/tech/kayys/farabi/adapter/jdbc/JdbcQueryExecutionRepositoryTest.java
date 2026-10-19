package tech.kayys.farabi.adapter.jdbc;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JdbcQueryExecutionRepositoryTest {
    @Test void repositoryRequiresDataSource() {
        assertThrows(NullPointerException.class, () -> new JdbcQueryExecutionRepository(null));
    }
}
