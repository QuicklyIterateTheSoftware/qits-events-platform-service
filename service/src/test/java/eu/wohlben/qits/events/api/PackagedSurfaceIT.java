package eu.wohlben.qits.events.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.events.stream.FakeSubscriber;
import eu.wohlben.qits.events.testdb.EmbeddedPg;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The surface of the <em>packaged artifact</em> — the fast-jar under {@code ./mvnw verify
 * -DskipITs=false}, the GraalVM binary under {@code -Dnative} — because that is the only place a
 * whole class of failure is visible.
 *
 * <p>Every other test here is a {@code @QuarkusTest}: it augments and runs in the build JVM, with
 * the full classpath present, reflection unrestricted, its datasource keys handed to it by a config
 * source — and, crucially, <b>Quinoa disabled</b>. Quinoa is off by default in test mode, so no
 * {@code @QuarkusTest} in this repo has ever seen the client at all; a unit test asserting something
 * about the served client would pass against a process with no client in it. What the SPA is
 * actually served as is proven here or nowhere.
 *
 * <p><b>The client is served at the root</b> since this service got a host of its own
 * ({@code events.<env>.<domain>}). The segment survives only as the wire prefix, which is what the
 * probe list below turns on:
 *
 * <ul>
 *   <li>{@code /} → 200 HTML carrying {@code <base href="/">} — the client's own spelling, set in
 *       another repository's {@code angular.json}, where no build here can check it. Wrong, and the
 *       page loads and then fetches its JavaScript from nowhere.
 *   <li>a deep link, scoped and unscoped → 200 {@code index.html}, so the Angular router owns it
 *       across a reload
 *   <li>{@code /events/} → 404: the whole segment is ignored by SPA routing now, so the old address
 *       is not a second door into the client. The edge sends the bookmark on with a redirect.
 *   <li>{@code /events/api/<real>} → the API's own answer; {@code /events/api/nope} → 404 and not
 *       the client. A machine client parses {@code index.html} as data.
 *   <li>the readiness endpoint the deployer's health gate curls, at the address the deployment
 *       assumes
 *   <li>{@code /events/stream}: a plain GET → 404 and not the client, and the upgrade → a working
 *       socket. Two probes rather than one, because they fail for opposite reasons —
 *       websockets-next claims only the <em>handshake</em>, so the plain GET is the Quinoa question
 *       and the upgrade is the "did the endpoint survive augmentation and the native image?"
 *       question. qits-ci learned the first by measuring it: before the prefix was ignored, a plain
 *       GET on its daemon socket answered 200 {@code index.html} from a green build.
 * </ul>
 *
 * <p>ITs are skipped by default ({@code skipITs} in the root pom) because they need a `package`, and
 * a package here needs the webui submodule and a node on PATH — neither of which the clone-alone
 * rule promises. Ask for them explicitly.
 */
@QuarkusIntegrationTest
@TestProfile(PackagedSurfaceIT.PackagedUnderTarget.class)
public class PackagedSurfaceIT {

  /** What the client's index.html spells now that it is mounted at the root of its own host. */
  private static final String BASE_HREF = "<base href=\"/\">";

  /**
   * Hands the launched artifact a database the way a deployment does — as the generic resource
   * triple, not as the datasource keys. The events jar ships {@code
   * jdbc.url=${QITS_RESOURCE_DB_URL}} and its two siblings, so supplying the variables leaves the
   * <b>shipped</b> expression itself under test (the AUTO_SERVER lesson, applied to what replaced
   * that URL). Expression expansion reads the whole config, and these overrides reach the launched
   * process as system properties, so the same three names resolve.
   *
   * <p>The database is an embedded postgres this JVM starts, on a name of its own so this IT and
   * {@code PackagedLogBridgeIT} cannot write into each other's schema. <b>Its url travels through a
   * system property rather than a static field</b>: a test profile is instantiated in more than one
   * classloader, so a field written by one copy is not the field the other reads, while the process
   * has exactly one property table.
   */
  public static class PackagedUnderTarget implements QuarkusTestProfile {

    /** Where the url is parked for whichever copy of this class is asked second. */
    private static final String URL_PROPERTY = "qits.test.packaged-surface-it.db-url";

    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "QITS_RESOURCE_DB_URL", databaseUrl(),
          "QITS_RESOURCE_DB_USERNAME", EmbeddedPg.USER,
          "QITS_RESOURCE_DB_PASSWORD", EmbeddedPg.PASSWORD);
    }

    private static synchronized String databaseUrl() {
      String recorded = System.getProperty(URL_PROPERTY);
      if (recorded != null) {
        return recorded;
      }
      // localhost resolves for the launched process too — it is a child of this JVM on this host.
      String url = EmbeddedPg.url("events_packaged_it");
      System.setProperty(URL_PROPERTY, url);
      return url;
    }
  }

  /**
   * The socket's absolute literal. {@code /events/stream} does not follow {@code quarkus.rest.path}
   * — it carries the segment itself — and it is the address a publisher's config derives, so it is
   * spelled here in full rather than built from a relative one.
   */
  @TestHTTPResource("/events/stream")
  URI stream;

  @Test
  public void theClientIsServedAtTheRootWithItsOwnBaseHref() {
    String html =
        given().when().get("/").then().statusCode(200).contentType(ContentType.HTML).extract()
            .asString();
    assertTrue(
        html.contains(BASE_HREF),
        "the client's baseHref must be the root it is mounted at; got: "
            + html.substring(0, Math.min(400, html.length())));
  }

  @Test
  public void aDeepLinkFallsBackToTheClientSoItsRouterOwnsIt() {
    String deepLink =
        given().when().get("/some/route").then().statusCode(200).contentType(ContentType.HTML)
            .extract()
            .asString();
    assertTrue(
        deepLink.contains(BASE_HREF),
        "a deep link must answer with index.html, not with a differently-shaped page");

    // The scoped form of the same page. `/qits/events/<id>` is one address the client routes and
    // the server knows nothing about, and it has to survive a reload like any other.
    String scoped =
        given()
            .when()
            .get("/qits/events/" + UUID.randomUUID())
            .then()
            .statusCode(200)
            .contentType(ContentType.HTML)
            .extract()
            .asString();
    assertTrue(scoped.contains(BASE_HREF), "a project-scoped deep link must answer with index.html");
  }

  @Test
  public void theOldSegmentIsNoLongerADoorIntoTheClient() {
    // The whole /events prefix is in quarkus.quinoa.ignored-path-prefixes, so nothing under it is
    // rerouted to index.html. An old bookmark is the edge's problem, answered with a redirect
    // there — here it is an honest 404.
    String body = given().when().get("/events/").then().statusCode(404).extract().asString();
    assertFalse(body.contains(BASE_HREF), "the old segment must not serve the client; got: " + body);
  }

  @Test
  public void realRoutesAnswerAndAMistypedOneIsNeverHtml() {
    given().when().get("/events/api/events").then().statusCode(200).contentType(ContentType.JSON);

    // The whole reason quarkus.quinoa.ignored-path-prefixes is set: without /api in that list this
    // answers 200 with index.html, and a machine client parses the client's not-found page as data.
    //
    // The assertion is "404, and not the CLIENT" rather than the reference's shorter "404, never
    // HTML", because what actually comes back here is Vert.x' own stock 53-byte
    // `<h1>Resource not found</h1>` — text/html, and correct. Every sibling service answers a
    // mistyped machine path the same way; nothing in the platform installs a JSON 404 handler for
    // unrouted paths, and asserting on the content type alone would fail against the right
    // behaviour while still passing against the wrong one (index.html is text/html too). So the
    // status and the absence of the client are what is pinned.
    String body =
        given().when().get("/events/api/nope").then().statusCode(404).extract().asString();
    assertFalse(
        body.contains(BASE_HREF),
        "a mistyped machine path must not be answered with the client; got: " + body);

    // The edge path-routes verbatim by prefix, so there is no unprefixed form to fall back to —
    // and at the root an unprefixed /api/events is the CLIENT's ground, which is why the assertion
    // is that it never answers as the API rather than that it 404s.
    given().when().get("/api/events").then().statusCode(200).contentType(ContentType.HTML);
  }

  @Test
  public void theVocabularyRouteAnswersJsonAndNotTheClient() {
    // /events/api/events/names is a literal beside the /{id} template, and it is the newest place
    // the two ways this can go wrong meet: JAX-RS could match the template (404, "Event not found:
    // names") or — if /api ever left quarkus.quinoa.ignored-path-prefixes — Quinoa's catch-all could
    // answer 200 index.html, which a filter's dropdown would parse as data. Both are invisible to a
    // @QuarkusTest, where Quinoa is disabled and there is no client at all.
    given()
        .contentType(ContentType.JSON)
        .body("{\"name\":\"PackagedVocabulary\",\"occurredAt\":\"2026-07-31T09:00:00Z\"}")
        .when()
        .post("/events/api/events")
        .then()
        .statusCode(200);

    given()
        .when()
        .get("/events/api/events/names")
        .then()
        .statusCode(200)
        .contentType(ContentType.JSON)
        .body("names", org.hamcrest.Matchers.hasItem("PackagedVocabulary"));
  }

  @Test
  public void aPageOfTheLogStopsAtTheLimitAndSaysWhereToResume() {
    // On the artifact because the page is a Flyway-migrated index away from being a scan, and
    // because `nextCursor` is the one field the SPA's "load more" is built on: an omit-nulls mapper
    // or a dropped record component would leave a client unable to tell "no more" from "no field".
    String mark = "packaged-page-" + System.nanoTime();
    for (int i = 1; i <= 3; i++) {
      given()
          .contentType(ContentType.JSON)
          .body(
              "{\"name\":\"PackagedPage\",\"occurredAt\":\"2026-0"
                  + i
                  + "-01T00:00:00Z\",\"payload\":\"{\\\"mark\\\":\\\""
                  + mark
                  + "\\\"}\"}")
          .when()
          .post("/events/api/events")
          .then()
          .statusCode(200);
    }

    String cursor =
        given()
            .queryParam("q", mark)
            .queryParam("limit", 2)
            .when()
            .get("/events/api/events")
            .then()
            .statusCode(200)
            .body("events", org.hamcrest.Matchers.hasSize(2))
            .body("nextCursor", org.hamcrest.Matchers.notNullValue())
            .extract()
            .path("nextCursor");

    given()
        .queryParam("q", mark)
        .queryParam("limit", 2)
        .queryParam("cursor", cursor)
        .when()
        .get("/events/api/events")
        .then()
        .statusCode(200)
        .body("events", org.hamcrest.Matchers.hasSize(1))
        .body("$", org.hamcrest.Matchers.hasKey("nextCursor"))
        .body("nextCursor", org.hamcrest.Matchers.nullValue());
  }

  @Test
  public void theReadinessEndpointIsWhereTheDeploymentLooksForIt() {
    given()
        .when()
        .get("/events/q/health/ready")
        .then()
        .statusCode(200)
        .body("status", org.hamcrest.Matchers.equalTo("UP"));
  }

  @Test
  public void theApiDocumentAndItsUiAreServedUnderTheSegment() {
    // Both live under quarkus.http.non-application-root-path, which sits OUTSIDE quarkus.rest.path
    // and carries /events on its own; at / they would be the client's ground now.
    given().when().get("/events/q/openapi").then().statusCode(200);
    given().when().get("/events/q/swagger-ui/").then().statusCode(200);
  }

  @Test
  public void anEventRoundTripsThroughFlywayAndPanacheOnTheShippedDatasource() {
    String id =
        given()
            .contentType(ContentType.JSON)
            .body("{\"name\":\"Packaged surface\",\"occurredAt\":\"2026-07-31T09:00:00Z\"}")
            .when()
            .post("/events/api/events")
            .then()
            .statusCode(200)
            .extract()
            .path("event.id");

    given()
        .when()
        .get("/events/api/events/" + id)
        .then()
        .statusCode(200)
        .body("event.name", org.hamcrest.Matchers.equalTo("Packaged surface"));

    // The round trip above would look identical against any database at all, so read the row back
    // out of the postgres this JVM handed the process through ${QITS_RESOURCE_DB_URL}. That is the
    // whole claim: the shipped expression resolved, Flyway's migration survived as a classpath
    // resource (exactly the shape a native image drops), and the table it created is the one the
    // request wrote into.
    assertTrue(rowExists(id), "the packaged process must have written into the resource database");
  }

  private static boolean rowExists(String id) {
    String url = EmbeddedPg.url("events_packaged_it");
    try (Connection connection =
            DriverManager.getConnection(url, EmbeddedPg.USER, EmbeddedPg.PASSWORD);
        PreparedStatement query =
            connection.prepareStatement("select 1 from event where id = ?")) {
      query.setString(1, id);
      try (ResultSet found = query.executeQuery()) {
        return found.next();
      }
    } catch (Exception e) {
      throw new IllegalStateException("could not read the resource database back", e);
    }
  }

  @Test
  public void aPlainGetOnTheSocketPathIsFourOhFourAndNeverTheClient() {
    // websockets-next claims the UPGRADE and nothing else, so a GET with no Upgrade header reaches
    // no socket route at all and — without /stream in quarkus.quinoa.ignored-path-prefixes — falls
    // through to the SPA's catch-all and answers 200 index.html. Measured exactly that way on
    // qits-ci's /ci/daemon. A subscriber handed a web page parses it as data; 404 is the answer.
    //
    // As everywhere else here the assertion is "404, and not the CLIENT" rather than "404, never
    // HTML": what actually comes back is Vert.x' own stock <h1>Resource not found</h1>, which is
    // text/html and correct, so the absence of the client is what is pinned.
    String body = given().when().get("/events/stream").then().statusCode(404).extract().asString();
    assertFalse(
        body.contains(BASE_HREF), "the stream path must not be answered with the client; got: " + body);

    String mistyped =
        given().when().get("/events/stream/nope").then().statusCode(404).extract().asString();
    assertFalse(mistyped.contains(BASE_HREF));
  }

  @Test
  public void theStreamIsOnTheArtifactsRouterAndPushesWhatThePublishWrote() throws Exception {
    // Ignoring a prefix stops the SPA REROUTE; it does not unregister the real route. That is the
    // half of the previous test's arrangement that only a real upgrade can prove — and the endpoint
    // is registered at AUGMENTATION, so under -Dnative this is where "the extension is
    // native-image supported" stops being a claim. A dropped route fails the upgrade with a 404
    // instead, and every subscriber would otherwise see only a stream that never says anything.
    String signature = "PackagedProbe" + System.nanoTime();
    String envelope =
        "{\"name\":\""
            + signature
            + "\",\"occurredAt\":\"2026-07-31T12:46:03Z\",\"payload\":\"{\\\"probe\\\":true}\","
            + "\"description\":null}";

    try (FakeSubscriber subscriber = FakeSubscriber.dial(stream)) {
      subscriber.subscribe(signature);
      // The protocol has no ack and there is no bean to inspect from out here — the app under test
      // is another process. So a fresh event is published until one of them lands as a frame: each
      // attempt is its own UUID and therefore its own create, and a create is what pushes.
      String frame = null;
      long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
      while (frame == null && System.nanoTime() < deadline) {
        given()
            .contentType(ContentType.JSON)
            .body(envelope)
            .when()
            .put("/events/api/events/" + UUID.randomUUID())
            .then()
            .statusCode(201);
        frame = subscriber.next(Duration.ofSeconds(2));
      }
      assertNotNull(frame, "the packaged artifact's stream pushed nothing");
      assertTrue(frame.contains("\"name\":\"" + signature + "\""), frame);
      assertTrue(frame.contains("\"payload\":\"{\\\"probe\\\":true}\""), frame);
      // The envelope above never mentions parentId or environment — an older publisher's exact
      // bytes — and the frame carries both as explicit nulls all the same. Both halves of the
      // compatibility clause in one assertion, on the artifact.
      assertTrue(frame.contains("\"parentId\":null"), frame);
      assertTrue(frame.contains("\"environment\":null"), frame);
    }
  }

  @Test
  public void theIdempotentPublishAnswersTwoOhOneThenTwoHundredThenFourHundred() {
    // On the artifact rather than only in a @QuarkusTest, because this is also the only place the
    // payload, parent_id and environment columns are exercised against the provisioned database
    // through Flyway's real migration resources — the shape a native image drops silently.
    //
    // The envelope carries parentId and environment here for exactly that reason: a migration that
    // never ran, a column MapStruct maps by a name the native image dropped, or an omit-nulls
    // mapper are all invisible to a @QuarkusTest and all fatal to the publisher that ships next.
    String parent = UUID.randomUUID().toString();
    String id = UUID.randomUUID().toString();
    String envelope =
        "{\"name\":\"PackagedPublish\",\"occurredAt\":\"2026-07-31T12:46:03Z\","
            + "\"payload\":\"{\\\"repoId\\\":\\\"qits-events\\\"}\",\"description\":null,"
            + "\"environment\":\"dev\",\"parentId\":\""
            + parent
            + "\"}";

    given()
        .contentType(ContentType.JSON)
        .body(envelope)
        .when()
        .put("/events/api/events/" + id)
        .then()
        .statusCode(201)
        .body("event.payload", org.hamcrest.Matchers.equalTo("{\"repoId\":\"qits-events\"}"))
        .body("event.parentId", org.hamcrest.Matchers.equalTo(parent))
        .body("event.environment", org.hamcrest.Matchers.equalTo("dev"));

    given()
        .contentType(ContentType.JSON)
        .body(envelope)
        .when()
        .put("/events/api/events/" + id)
        .then()
        .statusCode(200);

    // Read back through the list route's ?parentId= filter, which is the read model this feature
    // adds and the one query the V3 index exists for. The parent itself was never published — a
    // dangling cause is data, so the children query answers about it all the same.
    given()
        .queryParam("parentId", parent)
        .when()
        .get("/events/api/events")
        .then()
        .statusCode(200)
        .body("events.id", org.hamcrest.Matchers.contains(id));

    // ... and through ?environment=, the tier read model, against the real V2 column and its index.
    given()
        .queryParam("environment", "dev")
        .queryParam("name", "PackagedPublish")
        .when()
        .get("/events/api/events")
        .then()
        .statusCode(200)
        .body("events.id", org.hamcrest.Matchers.hasItem(id));

    given()
        .contentType(ContentType.JSON)
        .body(envelope.replace("qits-events\\\"}", "somebody-else\\\"}"))
        .when()
        .put("/events/api/events/" + id)
        .then()
        .statusCode(400);

    // The cause is inside the comparison too: one id may not be re-published under another parent.
    given()
        .contentType(ContentType.JSON)
        .body(envelope.replace(parent, UUID.randomUUID().toString()))
        .when()
        .put("/events/api/events/" + id)
        .then()
        .statusCode(400);

    given()
        .contentType(ContentType.JSON)
        .body(envelope)
        .when()
        .put("/events/api/events/not-a-uuid")
        .then()
        .statusCode(400);
  }

  @Test
  public void aRootEventCarriesTheParentIdKeyOnTheWire() {
    // hasKey, not nullValue(): an absent JSON path reads as null too, and the difference is the
    // whole of what a consumer can rely on — the publisher that ships next probes for this key to
    // decide whether this service knows about causation. Asserted on the ARTIFACT because an
    // omit-nulls Jackson customizer or a dropped record component is exactly the class of change a
    // @QuarkusTest cannot distinguish from the right behaviour.
    String id =
        given()
            .contentType(ContentType.JSON)
            .body("{\"name\":\"PackagedRoot\",\"occurredAt\":\"2026-07-31T09:00:00Z\"}")
            .when()
            .post("/events/api/events")
            .then()
            .statusCode(200)
            .extract()
            .path("event.id");

    given()
        .when()
        .get("/events/api/events/" + id)
        .then()
        .statusCode(200)
        .body("event", org.hamcrest.Matchers.hasKey("parentId"))
        .body("event.parentId", org.hamcrest.Matchers.nullValue())
        // The tier key is pinned the same way, for the same probing consumer.
        .body("event", org.hamcrest.Matchers.hasKey("environment"))
        .body("event.environment", org.hamcrest.Matchers.nullValue());
  }
}
