package eu.wohlben.qits.events.security;

import static eu.wohlben.qits.events.security.BearerTokens.bearer;
import static eu.wohlben.qits.events.security.BearerTokens.cliToken;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.events.stream.FakeSubscriber;
import eu.wohlben.qits.events.stream.SseReader;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A person's bearer token, checked by the real quarkus-oidc against a local key, beside the
 * forward-auth headers that in-network callers keep sending.
 *
 * <p>Why tokens reach this service at all: a person's command-line tool calls through the edge with
 * that person's token, and the edge strips the {@code X-Qits-*} headers from any request that
 * carries one. So the token is the only thing that can say who the person is, and its {@code
 * groups} claim is what the {@code @RolesAllowed} boundaries read.
 */
@QuarkusTest
@TestProfile(BearerAuthProfile.class)
class BearerAuthTest {

  private static final String NAMES = "/events/api/events/names";
  private static final String LOG = "/events/api/events";

  private static final Map<String, String> IN_NETWORK =
      Map.of("X-Qits-User", "qits-eventstream", "X-Qits-Roles", "qits:system");

  @TestHTTPResource("/events/api/stream")
  URI sse;

  @TestHTTPResource("/events/stream")
  URI socket;

  // --- a person's CLI token -----------------------------------------------------------------------

  @Test
  void aCliTokenBecomesTheIdentityWithItsGroupsAsRoles() {
    given()
        .header("Authorization", bearer(cliToken("qits:admin")))
        .when()
        .get("/events/api/test-identity")
        .then()
        .statusCode(200)
        .body("anonymous", equalTo(false))
        .body("principal", equalTo(BearerTokens.SUBJECT))
        .body("roles", contains("qits:admin"));
  }

  @Test
  void aCliTokenReadsTheEventNames() {
    given()
        .header("Authorization", bearer(cliToken("qits:admin")))
        .when()
        .get(NAMES)
        .then()
        .statusCode(200);
  }

  @Test
  void aCliTokenOpensTheSseStream() throws Exception {
    try (SseReader reader =
        SseReader.open(sse, Map.of("Authorization", bearer(cliToken("qits:admin"))))) {
      assertEquals(200, reader.status());
      assertTrue(
          reader.contentType().startsWith("text/event-stream"),
          "an opened stream must answer as SSE; got: " + reader.contentType());
      assertNotNull(reader.nextLine(Duration.ofSeconds(10)), "the opened stream said nothing");
    }
  }

  @Test
  void aCliTokenOpensTheSocket() {
    assertDoesNotThrow(
        () ->
            FakeSubscriber.dial(socket, Map.of("Authorization", bearer(cliToken("qits:admin"))))
                .close());
  }

  @Test
  void aTokenForThisServicesOwnAudienceIsAcceptedToo() {
    // Quarkus accepts a token when its `aud` holds any one of the configured values.
    given()
        .header(
            "Authorization",
            bearer(BearerTokens.tokenFor(BearerTokens.OWN_AUDIENCE, "qits:admin")))
        .when()
        .get(NAMES)
        .then()
        .statusCode(200);
  }

  // --- tokens that do not get in ------------------------------------------------------------------

  @Test
  void aTokenWithoutAnAllowedRoleIsForbidden() {
    // It authenticates, so the answer is 403 and not 401: the person is known, the role is not
    // granted. `qits:reader` is outside both roles these doors admit.
    String reader = bearer(cliToken("qits:reader"));
    given().header("Authorization", reader).when().get(NAMES).then().statusCode(403);
    given()
        .header("Authorization", reader)
        .header("Accept", "text/event-stream")
        .when()
        .get("/events/api/stream")
        .then()
        .statusCode(403);
    assertThrows(
        Exception.class, () -> FakeSubscriber.dial(socket, Map.of("Authorization", reader)));
  }

  @Test
  void aTokenForAnotherServiceIsUnauthorized() {
    given()
        .header("Authorization", bearer(BearerTokens.tokenFor("qits-projects", "qits:admin")))
        .when()
        .get(NAMES)
        .then()
        .statusCode(401);
  }

  @Test
  void aTokenFromAnUntrustedSignerIsUnauthorized() {
    given()
        .header("Authorization", bearer(BearerTokens.foreignToken("qits:admin")))
        .when()
        .get(NAMES)
        .then()
        .statusCode(401);
  }

  @Test
  void aBadTokenIsUnauthorizedEvenBesideForwardAuthHeaders() {
    // The token decides whenever there is one: OIDC's mechanism runs before forward-auth.
    given()
        .header("Authorization", bearer(BearerTokens.foreignToken("qits:admin")))
        .header("X-Qits-User", "alice")
        .header("X-Qits-Roles", "qits:admin")
        .when()
        .get(NAMES)
        .then()
        .statusCode(401);
  }

  // --- in-network callers, unchanged --------------------------------------------------------------

  @Test
  void anInNetworkCallerWithHeadersAndNoTokenStillReadsTheLog() {
    given().headers(IN_NETWORK).when().get(LOG + "?limit=1").then().statusCode(200);
  }

  @Test
  void anInNetworkCallerWithHeadersAndNoTokenStillOpensTheSocket() {
    assertDoesNotThrow(() -> FakeSubscriber.dial(socket, IN_NETWORK).close());
  }

  // --- no credential at all -----------------------------------------------------------------------

  @Test
  void noCredentialIsUnauthorized() {
    given().when().get(NAMES).then().statusCode(401);
    given()
        .header("Accept", "text/event-stream")
        .when()
        .get("/events/api/stream")
        .then()
        .statusCode(401);
    assertThrows(Exception.class, () -> FakeSubscriber.dial(socket, Map.of()));
  }
}
