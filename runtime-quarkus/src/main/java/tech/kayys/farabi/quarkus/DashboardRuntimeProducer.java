package tech.kayys.farabi.quarkus;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import javax.sql.DataSource;
import tech.kayys.farabi.engine.DashboardRuntime;
import tech.kayys.farabi.jdbc.JdbcDashboardManifestRepository;

/**
 * Host applications must provide a governed DashboardRuntime.DatasetAccess bean.
 * Without one the HTTP endpoint is unavailable, not publicly readable.
 */
@ApplicationScoped
public class DashboardRuntimeProducer {
 @Inject DataSource dataSource;
 @Inject Instance<DashboardRuntime.DatasetAccess> access;

 @Produces
 @ApplicationScoped
 public DashboardRuntime runtime() {
  if(access.isUnsatisfied()||access.isAmbiguous())
   throw new IllegalStateException("A single governed DatasetAccess adapter is required");
  return new DashboardRuntime(
   new JdbcDashboardManifestRepository(dataSource,new ObjectMapper()),access.get());
 }
}
