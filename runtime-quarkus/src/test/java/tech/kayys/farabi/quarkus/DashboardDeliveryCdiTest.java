package tech.kayys.farabi.quarkus;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Exercises the actual Quarkus HTTP/CDI boundary.
 * No AuthenticatedContext bean is installed in this test application, so delivery must fail closed.
 */
@QuarkusTest
class DashboardDeliveryCdiTest {
    @Test
    void endpointReturnsServiceUnavailableWhenTrustedIdentityAdapterIsMissing() {
        given()
            .when()
            .get("/api/dashboards/00000000-0000-0000-0000-000000000001/delivery")
            .then()
            .statusCode(503);
    }
}
