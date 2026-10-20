package tech.kayys.farabi.quarkus;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import javax.sql.DataSource;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import tech.kayys.farabi.engine.DurableQueryService;
import tech.kayys.farabi.engine.QueryPorts.*;
import tech.kayys.farabi.jdbc.JdbcExecutionRepository;
import tech.kayys.farabi.dremio.DremioExecutor;

@ApplicationScoped
public class FarabiBeans {
    @Produces @Singleton ExecutionRepository repository(DataSource source) { return new JdbcExecutionRepository(source); }
    @Produces @Singleton Executor executor(@ConfigProperty(name="farabi.dremio.url") String url,
        @ConfigProperty(name="farabi.dremio.token") String token) {
        return new DremioExecutor(HttpClient.newHttpClient(),new ObjectMapper(),URI.create(url),token);
    }
    /** Deny by default until a real identity-backed policy adapter is installed. */
    @Produces @Singleton Authorizer authorizer() {
        return request -> { throw new SecurityException("Governance adapter not configured"); };
    }
    @Produces @Singleton Compiler compiler() {
        return request -> { throw new IllegalStateException("Semantic compiler not configured"); };
    }
    @Produces @Singleton DurableQueryService service(Authorizer a,Compiler c,Executor e,ExecutionRepository r) {
        return new DurableQueryService(a,c,e,r,Clock.systemUTC());
    }
}
