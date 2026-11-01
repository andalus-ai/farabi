package tech.kayys.farabi.quarkus;

import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.DashboardRuntime;
import tech.kayys.farabi.engine.RendererEnvelope;
import tech.kayys.farabi.engine.VisualizationContracts.Placement;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DashboardDeliveryResourceTest {
 private Instance<DashboardDeliveryResource.AuthenticatedContext> identity;
 private Instance<DashboardRuntime> runtime;
 private DashboardDeliveryResource.AuthenticatedContext context;
 private DashboardRuntime dashboardRuntime;
 private DashboardDeliveryResource resource;

 @BeforeEach @SuppressWarnings("unchecked")
 void setUp() {
  identity=mock(Instance.class);
  runtime=mock(Instance.class);
  context=mock(DashboardDeliveryResource.AuthenticatedContext.class);
  dashboardRuntime=mock(DashboardRuntime.class);
  resource=new DashboardDeliveryResource();
  resource.identity=identity;
  resource.runtime=runtime;
  when(identity.isUnsatisfied()).thenReturn(false);
  when(identity.isAmbiguous()).thenReturn(false);
  when(runtime.isUnsatisfied()).thenReturn(false);
  when(runtime.isAmbiguous()).thenReturn(false);
  when(identity.get()).thenReturn(context);
  when(runtime.get()).thenReturn(dashboardRuntime);
  when(context.requirePrincipal()).thenReturn(
      new DashboardDeliveryResource.Principal("trusted-workspace","trusted-user"));
 }

 @Test void returnsServiceUnavailableWhenIdentityAdapterIsMissingOrAmbiguous() {
  when(identity.isUnsatisfied()).thenReturn(true);
  assertEquals(503,resource.deliver(UUID.randomUUID().toString()).getStatus());
  verifyNoInteractions(dashboardRuntime);

  when(identity.isUnsatisfied()).thenReturn(false);
  when(identity.isAmbiguous()).thenReturn(true);
  assertEquals(503,resource.deliver(UUID.randomUUID().toString()).getStatus());
  verifyNoInteractions(dashboardRuntime);
 }

 @Test void returnsServiceUnavailableWhenRuntimeAdapterIsMissingOrAmbiguous() {
  when(runtime.isUnsatisfied()).thenReturn(true);
  assertEquals(503,resource.deliver(UUID.randomUUID().toString()).getStatus());
  when(runtime.isUnsatisfied()).thenReturn(false);
  when(runtime.isAmbiguous()).thenReturn(true);
  assertEquals(503,resource.deliver(UUID.randomUUID().toString()).getStatus());
  verifyNoInteractions(dashboardRuntime);
 }

 @Test void mapsMissingOrRejectedIdentityToUnauthorized() {
  when(context.requirePrincipal()).thenThrow(new SecurityException("not authenticated"));
  assertEquals(401,resource.deliver(UUID.randomUUID().toString()).getStatus());
  doReturn(null).when(context).requirePrincipal();
  assertEquals(401,resource.deliver(UUID.randomUUID().toString()).getStatus());
  verifyNoInteractions(dashboardRuntime);
 }

 @Test void rejectsBlankPrincipalFields() {
  assertThrows(SecurityException.class,
      ()->new DashboardDeliveryResource.Principal(" ","trusted-user"));
  assertThrows(SecurityException.class,
      ()->new DashboardDeliveryResource.Principal("trusted-workspace"," "));
 }

 @Test void rejectsMalformedManifestIdBeforeResolvingRuntime() {
  assertEquals(400,resource.deliver("not-a-uuid").getStatus());
  verify(runtime,never()).get();
  verifyNoInteractions(dashboardRuntime);
 }

 @Test void mapsDatasetAuthorizationFailureToForbidden() {
  when(dashboardRuntime.load(any(),anyString(),anyString(),anyInt()))
      .thenThrow(new SecurityException("dataset denied"));
  assertEquals(403,resource.deliver(UUID.randomUUID().toString()).getStatus());
 }

 @Test void mapsWorkspaceScopedManifestMissToNotFound() {
  when(dashboardRuntime.load(any(),anyString(),anyString(),anyInt()))
      .thenThrow(new IllegalArgumentException("manifest not found"));
  assertEquals(404,resource.deliver(UUID.randomUUID().toString()).getStatus());
 }

 @Test void passesOnlyTrustedPrincipalContextToDashboardRuntime() {
  UUID manifestId=UUID.randomUUID();
  var chart=new RendererEnvelope.Chart("chart-1","tenun.bar","Sales","sales",
      "region","total",null,List.of(),new Placement("chart-1",0,0,12,2));
  var dashboard=new RendererEnvelope.Dashboard(1,manifestId,1,"trusted-workspace",
      "Overview",List.of(chart));
  var rendered=new DashboardRuntime.Rendered(dashboard,List.of(
      new DashboardRuntime.ChartData("chart-1","tenun.bar",
          List.of(Map.of("region","west","total",3)))));
  when(dashboardRuntime.load(manifestId,"trusted-workspace","trusted-user",1000))
      .thenReturn(rendered);

  Response response=resource.deliver(manifestId.toString());
  assertEquals(200,response.getStatus());
  verify(dashboardRuntime).load(manifestId,"trusted-workspace","trusted-user",1000);
 }
}
