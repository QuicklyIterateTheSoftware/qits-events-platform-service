package eu.wohlben.qits.events.stream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.events.security.NoDevUserProfile;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The SSE stream's door, in the deployed posture — {@code ForwardAuthTest}'s arrangement, kept: the
 * {@code %test} dev-user fallback is blanked by {@link NoDevUserProfile}, so the gateway-injected
 * headers are the only identity there is and a {@code @RolesAllowed} boundary is really being
 * decided rather than waved through.
 *
 * <p><b>This is the one place the refusal is provable at all.</b> Every other {@code @QuarkusTest}
 * here runs with a dev user carrying all four platform roles, so the route's door is open before an
 * annotation is consulted; without this profile a test asserting "an unauthorised reader is refused"
 * would pass against a service with no door on it.
 *
 * <p>The roles are the socket's, exactly: {@code qits:admin} for a person's session and {@code
 * qits:system} for a machine. And the refusal is a <b>403 at connect</b> rather than an empty
 * stream, which is the decision worth a test of its own — an empty stream is indistinguishable from
 * an idle estate, so a browser chrome handed one would show a permanently calm indicator and never
 * learn it was blind.
 */
@QuarkusTest
@TestProfile(NoDevUserProfile.class)
class EventStreamResourceDoorTest {

  @TestHTTPResource("/events/api/stream")
  URI endpoint;

  @Test
  void anAdminSessionOpensTheStream() throws Exception {
    try (SseReader reader =
        SseReader.open(endpoint, Map.of("X-Qits-User", "alice", "X-Qits-Roles", "qits:admin"))) {
      assertEquals(200, reader.status());
      assertTrue(
          reader.contentType().startsWith("text/event-stream"),
          "an opened stream must answer as SSE; got: " + reader.contentType());
      // The opening comment: the head is flushed and the connection is live, not merely accepted.
      assertNotNull(reader.nextLine(Duration.ofSeconds(10)), "the opened stream said nothing");
    }
  }

  @Test
  void aMachineConsumerOpensTheStreamToo() throws Exception {
    try (SseReader reader =
        SseReader.open(endpoint, Map.of("X-Qits-User", "qits-ci", "X-Qits-Roles", "qits:system"))) {
      assertEquals(200, reader.status());
    }
  }

  @Test
  void aRoleOutsideTheBoundaryIsRefusedAtConnect() {
    // `qits:reader` for the same reason ForwardAuthTest names it: it is outside BOTH roles this
    // boundary admits, so the case still tests a boundary rather than a spelling.
    given()
        .header("X-Qits-User", "alice")
        .header("X-Qits-Roles", "qits:reader")
        .header("Accept", "text/event-stream")
        .when()
        .get("/events/api/stream")
        .then()
        .statusCode(403);
  }

  @Test
  void anAnonymousReaderIsRefusedAtConnect() {
    // No header at all is anonymous — "no name to record" — and anonymous holds no roles, so the
    // annotation refuses it. Note this is the annotation deciding, not this service inventing a
    // security state out of anonymity; see the note in ForwardAuthTest.
    given()
        .header("Accept", "text/event-stream")
        .when()
        .get("/events/api/stream")
        .then()
        .statusCode(401);
  }
}
