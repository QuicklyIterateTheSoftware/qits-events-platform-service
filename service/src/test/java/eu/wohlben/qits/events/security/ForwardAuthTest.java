package eu.wohlben.qits.events.security;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

/**
 * The deployed posture (dev-user fallback blanked): the gateway-injected header is the identity, and
 * its absence is anonymous rather than denied.
 *
 * <p>That last point is the one worth stating. Nothing in this service denies anything — there is no
 * authorization policy here, by design. So every assertion below is about <em>who the request is</em>,
 * never about a status code. A test that expected a 401 would be asserting a security control this
 * service does not have and must not grow.
 *
 * <p>The header is exercised through the real mechanism rather than through {@code @TestSecurity},
 * on purpose: the header <em>is</em> the contract, and an annotation that fabricates an identity
 * proves a path no deployment ever takes. qits-projects shipped a {@code SecurityIdentity} with no
 * mechanism behind it for a whole release that way.
 */
@QuarkusTest
@TestProfile(NoDevUserProfile.class)
class ForwardAuthTest {

  @Test
  void theGatewayInjectedHeaderEstablishesTheIdentity() {
    given()
        .header("X-Qits-User", "alice")
        .when()
        .get("/events/api/test-identity")
        .then()
        .statusCode(200)
        .body("anonymous", equalTo(false))
        .body("principal", equalTo("alice"));
  }

  @Test
  void noHeaderIsAnonymousAndStillServed() {
    // Anonymous means "no name to record", not a security state — the request proceeds.
    given()
        .when()
        .get("/events/api/test-identity")
        .then()
        .statusCode(200)
        .body("anonymous", equalTo(true));
  }

  @Test
  void aBlankHeaderIsAnonymousNotAnEmptyPrincipal() {
    given()
        .header("X-Qits-User", "  ")
        .when()
        .get("/events/api/test-identity")
        .then()
        .statusCode(200)
        .body("anonymous", equalTo(true));
  }

  @Test
  void theIdentityCarriesForwardedRoles() {
    // The service consumes the role set asserted from the same edge session as the user.
    // Jakarta security annotations then make the boundary decision from this identity.
    given()
        .header("X-Qits-User", "alice")
        .header("X-Qits-Roles", "qits:admin")
        .when()
        .get("/events/api/test-identity")
        .then()
        .statusCode(200)
        .body("principal", equalTo("alice"))
        .body("roles", contains("qits:admin"));
  }

  @Test
  void theAdminBoundaryAcceptsTheQitsAdminRole() {
    given()
        .header("X-Qits-User", "alice")
        .header("X-Qits-Roles", "qits:admin")
        .when()
        .get("/events/api/events")
        .then()
        .statusCode(200);
  }

  @Test
  void theAdminBoundaryRejectsAnotherRole() {
    // `qits-platform:admin` stood here until the two admin roles were collapsed into `qits:admin`
    // on 2026-09-06. It was the natural "different role" precisely because it LOOKED like an admin
    // one and was not this boundary's; with it retired, the case needs a role the platform grants
    // to nobody. `qits:system` will not do — the listing admits it beside `qits:admin` — so the
    // negative case has to name a role that is outside both, or it stops testing a boundary at all.
    given()
        .header("X-Qits-User", "alice")
        .header("X-Qits-Roles", "qits:reader")
        .when()
        .get("/events/api/events")
        .then()
        .statusCode(403);
  }
}
