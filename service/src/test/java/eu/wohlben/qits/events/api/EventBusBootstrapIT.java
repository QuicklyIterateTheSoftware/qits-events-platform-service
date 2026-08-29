package eu.wohlben.qits.events.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.events.stream.FakeSubscriber;
import eu.wohlben.qits.events.testdb.EmbeddedPg;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;

/**
 * The bus as it is <b>packaged</b> — the fast-jar under {@code ./mvnw verify -DskipITs=false}, the
 * GraalVM binary under {@code -Dnative} — telling the one story this repository exists for: an
 * event goes in once, comes out live, and is still there to be read afterwards by a consumer that
 * was not listening at the time.
 *
 * <p>{@link PackagedSurfaceIT} beside it already probes the artifact's <em>surface</em> — the
 * client at the root, the prefixes SPA routing must not swallow, a socket that upgrades. What it
 * does not do is tell a story: it is a list of properties, each true on its own, and the thing
 * qits-events actually promises the fleet is the sequence they make together. That sequence is what
 * is here.
 *
 * <p><b>Three of its steps exist only in a deployed posture, and no {@code @QuarkusTest} in this
 * repository can reach any of them:</b>
 *
 * <ul>
 *   <li><b>The doors are real.</b> Publishing takes the machine role and the reads take the
 *       person's ({@code EventController}), and the stream takes either
 *       ({@link eu.wohlben.qits.events.stream.EventStreamSocket}). Under {@code @QuarkusTest}
 *       qits-auth-core's {@code %test} dev-user hands every request all four platform roles
 *       before an annotation is consulted, so a suite cannot tell one door from another. Here
 *       nothing is injected — {@code ForwardAuthMechanism} is {@code LaunchMode.NORMAL}-guarded —
 *       and the identity is the edge's two headers or nothing.
 *   <li><b>The socket survived augmentation.</b> websockets-next registers {@code
 *       /events/stream} at AUGMENTATION and its class-level {@code @RolesAllowed} secures the
 *       HTTP <em>upgrade</em> (3.34's {@code SecurityHttpUpgradeCheck}), so both "the endpoint is
 *       there" and "the handshake is the door" are claims only a launched artifact can settle.
 *   <li><b>The log is the log.</b> The catch-up read runs through Flyway's real migration resources
 *       against the provisioned postgres — the {@code ${QITS_RESOURCE_DB_URL}} triple the
 *       events jar ships as an expression — and the composite cursor's index with it.
 * </ul>
 *
 * <p><b>There is no mock on the far side, and that is this service's shape rather than an
 * omission.</b> qits-events dials nobody: it has no upstream, its callers are the qits-eventstream
 * jars inside every sibling service, and the only thing it reaches out to is its own database and
 * (in a deployment) qits-observability, which the profile below darkens. So the far side of every
 * interaction below is a real client in this JVM — rest-assured as a publisher's outbox, {@link
 * FakeSubscriber} as a durable consumer's socket — and the server cannot tell either from the
 * article.
 *
 * <p>It is also this repo's first <b>userflow</b>: the proof doubles as documentation, emitted
 * under {@code service/target/userstories/} with the interactions drawn as a sequence diagram. Both
 * stories are browserless (an {@code Interactions} parameter and no {@code Flow}), so the
 * framework's transitive Playwright never launches anything — which is what lets this run in a step
 * container with no browser in it.
 *
 * <p><b>The module does not opt back into ITs for this.</b> {@code skipITs} stays true in the root
 * pom: {@link PackagedSurfaceIT} is half about the CLIENT — the base href, the deep links, the
 * fallback that must not swallow a machine path — and the userflow pipeline deliberately builds
 * without Quinoa ({@code -Dquarkus.quinoa=false}, since the qits-spa-events submodule arrives EMPTY
 * in a step container), while {@link eu.wohlben.qits.events.telemetry.PackagedLogBridgeIT} wants an
 * OTLP receiver this run has no use for. A blanket {@code -DskipITs=false} would make a
 * quinoa-less run red on tests that are about something else. {@code
 * .config/qits/ci-event-userflows.yml} names this class instead ({@code -DskipITs=false
 * "-Dit.test=EventBusBootstrapIT"}), which is also what keeps the userflow pipeline about these
 * stories and nothing else. A {@code -Dnative} build still runs every IT, this one included.
 *
 * <p><b>The two stories share one launched process and one log</b>, so neither may depend on the
 * other having run — JUnit orders methods deterministically but not meaningfully. They are kept
 * apart by <em>vocabulary</em>: every read below filters on {@code ?name=}, and the two stories
 * name different events. Nothing here counts the whole log.
 */
@QuarkusIntegrationTest
@TestProfile(EventBusBootstrapIT.PackagedWithItsOwnLog.class)
public class EventBusBootstrapIT {

  static final String CATEGORY = "event-bus";

  static final String DELIVERY_SLUG =
      "an-event-published-is-an-event-delivered-and-read-again-later";

  static final String REFUSAL_SLUG =
      "a-publisher-may-repeat-itself-never-contradict-itself-and-a-stranger-publishes-nothing";

  /** The prefixed wire paths, spelled in full — {@code quarkus.rest.path} is part of them. */
  private static final String EVENTS = "/events/api/events";

  private static final String READY = "/events/q/health/ready";

  /** The headers qits-gateway asserts and strips; qits-auth-core's defaults, unchanged here. */
  private static final String USER_HEADER = "X-Qits-User";

  private static final String ROLES_HEADER = "X-Qits-Roles";

  /**
   * The machine role a sibling service's eventstream jar carries, and the only one that publishes.
   */
  private static final String SYSTEM_ROLE = "qits:system";

  /** The person's role. It reads the log through the events client, and it writes nothing. */
  private static final String ADMIN_ROLE = "qits:admin";

  /** A real publisher: qits-ci's outbox is the busiest one on the platform. */
  private static final String PUBLISHER = "qits-ci";

  /**
   * The signature the consumer subscribes to first, and the one the warm-up publishes under.
   *
   * <p>It is a story beat rather than test scaffolding. The protocol has no acknowledgement —
   * {@code {"subscribe": [...]}} goes out and the server answers nothing — so the only way a
   * consumer learns its subscription is live is by <em>receiving something</em>. A publish that
   * raced the subscribe frame would otherwise be delivered to nobody and look exactly like a
   * broken fan-out.
   */
  static final String HANDSHAKE = "UserflowSubscriberHandshake";

  /** The event the delivery story is about. */
  static final String SIGNATURE = "UserflowBuildSucceeded";

  /**
   * The event the refusal story contests. It has a name of its own, so the two stories can never
   * read each other's rows.
   */
  static final String CONTESTED = "UserflowContestedPublish";

  /**
   * Two instants, and the order between them is load-bearing: the warm-up rows are older than the
   * story's event, so an ASCENDING read of this story's vocabulary reaches the watermark first and
   * the published event after it. Both are the PUBLISHER's times — {@code PUT} will not invent one,
   * because an event whose time this server chose could never replay equal to itself.
   */
  private static final String HANDSHAKE_AT = "2026-08-28T09:00:00Z";

  private static final String OCCURRED_AT = "2026-08-28T09:15:00Z";

  /**
   * Canonical JSON <em>in a string</em>, which is the whole of what this server knows about a
   * payload: it stores and compares these bytes verbatim and never parses, reformats or reorders
   * them. The publisher canonicalizes (qits-ci's qits-eventsourcing module) — a server that
   * pretty-printed this value would break the byte-for-byte equality the idempotent PUT rests on,
   * and the break would look like "publishers keep getting 400 on their own retries".
   */
  private static final String PAYLOAD = "{\"branch\":\"main\",\"repository\":\"qits-events\"}";

  /** What a contested retry sends the second time. One byte of difference is the whole test. */
  private static final String OTHER_PAYLOAD =
      "{\"branch\":\"somebody-elses\",\"repository\":\"qits-events\"}";

  /** How long a frame has to arrive before the fan-out is declared broken. */
  private static final Duration ARRIVAL = Duration.ofSeconds(20);

  /**
   * Hands the launched artifact its config the way a deployment does — and there is remarkably
   * little of it, which is this service's shape: {@code .config/qits/deployments.yml} declares
   * {@code resources: postgresql:db} and nothing else, so a qits-events deployment is a process, a
   * database and an address to export telemetry to.
   *
   * <p>The database is supplied as the platform's GENERIC resource triple rather than as datasource
   * keys, exactly as {@link PackagedSurfaceIT.PackagedUnderTarget} does: the events jar ships
   * {@code jdbc.url=${QITS_RESOURCE_DB_URL}} and its two siblings, so supplying the variables
   * leaves the <b>shipped expression itself</b> under test. It is a database of this IT's own on
   * this JVM's embedded postgres, so the three packaged-artifact ITs cannot write into each other's
   * schema — and it is fresh, which is why every event name below can be a readable literal instead
   * of a nonce.
   *
   * <p><b>Its url travels through a system property rather than a static field</b>, the trick both
   * ITs beside this one carry: a test profile is instantiated in more than one classloader, so a
   * field written by one copy is not the field the other reads, while the process has exactly one
   * property table.
   *
   * <p><b>Every key here is a RUNTIME key.</b> A packaged process takes its configuration as {@code
   * -D} arguments on an artifact that was already built, so a build-time key would be silently
   * ignored and these stories would prove something other than what they say. Everything that makes
   * this service what it is — {@code quarkus.rest.path}, the non-application root, the Quinoa
   * ignore list, the four OTel logging keys — is left exactly as it ships.
   */
  public static class PackagedWithItsOwnLog implements QuarkusTestProfile {

    /** Where the url is parked for whichever copy of this class is asked second. */
    private static final String URL_PROPERTY = "qits.test.event-bus-it.db-url";

    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "QITS_RESOURCE_DB_URL", databaseUrl(),
          "QITS_RESOURCE_DB_USERNAME", EmbeddedPg.USER,
          "QITS_RESOURCE_DB_PASSWORD", EmbeddedPg.PASSWORD,
          // Dark outside a deployment, like %dev/%test — a RUNTIME key, and a NEUTRALISATION rather
          // than tidiness: the shipped config exports to http://qits-observability:8080, a name
          // that resolves on qits-net and nowhere else, so a launched artifact would spend this run
          // retrying an export to a host that is not there and bury the story's own log under the
          // failures. There is nothing else to darken here — this service is the bus, so it carries
          // no qits-eventstream jar and publishes to nobody, itself included.
          "quarkus.otel.sdk.disabled", "true");
    }

    private static synchronized String databaseUrl() {
      String recorded = System.getProperty(URL_PROPERTY);
      if (recorded != null) {
        return recorded;
      }
      // localhost resolves for the launched process too — it is a child of this JVM on this host.
      String url = EmbeddedPg.url("events_userflow_it");
      System.setProperty(URL_PROPERTY, url);
      return url;
    }
  }

  /**
   * The socket's absolute literal. {@code /events/stream} does not follow {@code quarkus.rest.path}
   * — it carries the segment itself — and it is the address a consumer's config derives, so it is
   * spelled in full rather than built from a relative one.
   */
  @TestHTTPResource("/events/stream")
  URI stream;

  @UserStory(
      value = "An event published is an event delivered, and read again later",
      category = "event-bus")
  @UserStoryDescription(
      """
      One qits-events serves the whole platform, and every sibling service reaches it through the
      qits-eventstream jar it carries. This is the round trip that jar is written against.

      A durable consumer opens the stream with the machine credential the edge asserts and names
      the signatures it cares about. A publisher's outbox then PUTs an event under a UUID IT
      chose — the id is the idempotency key, which is what makes a retry safe — and the consumer
      is pushed the envelope while the write is still warm.

      Then the publisher's first attempt is retried, because a network dropped the answer rather
      than the request. The same id and the same bytes come back 200 instead of 201, nothing is
      written, and — the half that matters to every subscriber on the platform — NOTHING IS
      PUSHED. A consumer must not see an event twice because an acknowledgement went missing.

      Finally the consumer goes away, as a redeploy makes it, and comes back holding nothing but
      a watermark: the last row it handled. It reads the log FORWARD from there — ascending,
      which is the direction that exists for exactly this — and finds the event it would
      otherwise have missed, once, with the publisher's own bytes intact. Live delivery and
      catch-up are two readings of one log, and a consumer that used both must never see a row
      twice or miss one.
      """)
  void anEventIsDeliveredLiveRetriedSafelyAndFoundAgainOnCatchUp(Interactions story)
      throws Exception {
    story.note("qits-events starts as the platform's one bus, its log on the provisioned postgres");
    given().get(READY).then().statusCode(200).body("status", equalTo("UP"));

    String id = UUID.randomUUID().toString();
    String envelope = envelope(SIGNATURE, OCCURRED_AT, PAYLOAD);

    try (FakeSubscriber consumer = FakeSubscriber.dial(stream, machineCredential())) {
      // The subscription replaces the connection's set and covers both names at once — the
      // handshake the consumer proves itself live with, and the signature it is here for. A
      // connection that has named nothing is subscribed to nothing, which is why this comes first.
      consumer.subscribe(HANDSHAKE, SIGNATURE);
      awaitSubscriptionIsLive(consumer);
      story
          .happened(
              "a durable consumer",
              "qits-events",
              "WS /events/stream (X-Qits-Roles: qits:system), then {\"subscribe\":[…]}")
          .as("subscribed");

      // --- the publish. 201, because the id is one nothing has seen: the row was created AND
      // announced. The envelope carries the tier the publisher ran in, which is inside the replay
      // comparison below.
      publisher()
          .body(envelope)
          .when()
          .put(EVENTS + "/" + id)
          .then()
          .statusCode(201)
          .body("event.id", equalTo(id))
          .body("event.payload", equalTo(PAYLOAD))
          .body("event.environment", equalTo("platform"));
      story
          .happened(
              "a publisher's outbox",
              "qits-events",
              "PUT /events/api/events/{id} (an id nothing has seen) -> 201")
          .as("published");

      // --- the delivery. The frame is EventCreated's own JSON, which IS the wire contract; the
      // fields asserted are the ones a consumer routes and deduplicates on.
      String frame = awaitFrame(consumer, SIGNATURE);
      assertNotNull(frame, "the packaged artifact's stream pushed nothing for a published event");
      assertTrue(frame.contains("\"id\":\"" + id + "\""), frame);
      assertTrue(frame.contains("\"name\":\"" + SIGNATURE + "\""), frame);
      assertTrue(frame.contains("\"payload\":\"" + PAYLOAD.replace("\"", "\\\"") + "\""), frame);
      story
          .happened("qits-events", "a durable consumer", "the EventCreated frame, live")
          .as("delivered");

      // Anything the warm-up left in flight is drained here, so that the silence asserted below is
      // the replay's silence and not an empty queue that happened to have been emptied already.
      drain(consumer);

      // --- the retry. Same id, same bytes: the publisher's first attempt, sent again because its
      // answer was lost. 200 rather than 201 — nothing written — and no frame, which is the clause
      // every subscriber on the platform depends on and the one a status code alone cannot show.
      publisher().body(envelope).when().put(EVENTS + "/" + id).then().statusCode(200);
      assertNull(
          consumer.next(Duration.ofSeconds(2)),
          "a replayed publish must push nothing — a subscriber may not see an event twice because"
              + " an acknowledgement was lost");
      story
          .happened(
              "a publisher's outbox",
              "qits-events",
              "PUT /events/api/events/{id} (the same bytes again) -> 200, and nothing is pushed")
          .as("replayed-silently");
    }

    // --- catch-up. The socket is closed: the consumer restarted, and all it kept is a watermark.
    story.note("the consumer is restarted and comes back holding only its watermark");

    // The watermark itself, taken the way a consumer takes one — the cursor beside the last row it
    // handled, reading FORWARD one row at a time over the vocabulary it subscribes to. `nextCursor`
    // being non-null is the only end-of-log signal there is, and a full page is not one.
    JsonPath handled =
        consumer()
            .queryParam("name", HANDSHAKE + "," + SIGNATURE)
            .queryParam("order", "asc")
            .queryParam("limit", 1)
            .when()
            .get(EVENTS)
            .then()
            .statusCode(200)
            .body("events", hasSize(1))
            .body("nextCursor", notNullValue())
            .extract()
            .jsonPath();
    String watermark = handled.getString("nextCursor");
    String alreadyHandled = handled.getString("events[0].id");
    story
        .happened(
            "a durable consumer",
            "qits-events",
            "GET /events/api/events?name=…&order=asc&limit=1 (the watermark)")
        .as("watermark-taken");

    // Forward from it: the row it already handled is never handed out a second time, and the event
    // published while it was away is there — ONCE, which is the retry's 200 seen from the log's end
    // rather than from the publisher's.
    consumer()
        .queryParam("name", HANDSHAKE + "," + SIGNATURE)
        .queryParam("order", "asc")
        .queryParam("cursor", watermark)
        .when()
        .get(EVENTS)
        .then()
        .statusCode(200)
        .body("events.id", not(hasItem(alreadyHandled)))
        .body("events.id", hasItem(id));
    story
        .happened(
            "a durable consumer",
            "qits-events",
            "GET /events/api/events?order=asc&cursor=… (forward from the watermark)")
        .as("caught-up");

    // …and the row it found is the publisher's own event, byte for byte, with the head of the log
    // named: `nextCursor` null is what tells a catch-up consumer it has arrived and may go back to
    // listening. Filtered to this story's signature, so `hasSize(1)` is also the statement that the
    // retry wrote no second row.
    consumer()
        .queryParam("name", SIGNATURE)
        .queryParam("order", "asc")
        .when()
        .get(EVENTS)
        .then()
        .statusCode(200)
        .body("events", hasSize(1))
        .body("events[0].id", equalTo(id))
        .body("events[0].payload", equalTo(PAYLOAD))
        .body("events[0].environment", equalTo("platform"))
        .body("nextCursor", nullValue());
    story
        .happened(
            "a durable consumer",
            "qits-events",
            "GET /events/api/events?name=… -> one row, the publisher's bytes, and the head")
        .as("log-holds-it-once");
  }

  @UserStory(
      value =
          "A publisher may repeat itself, never contradict itself — and a stranger publishes"
              + " nothing",
      category = "event-bus")
  @UserStoryDescription(
      """
      The flip side of a bus whose write is safe to retry. Idempotency is only worth something
      if the server can tell a retry from a lie, and this is where that line is drawn — plus the
      doors, which decide who is allowed to draw near it at all.

      A UUID reused for different content is refused with 400 and not with a conflict to sit and
      poll on: no retry fixes a publisher that reused an id, so telling it to try again would be
      a wait with no end. The comparison covers the occurrence and not the prose about it —
      change the payload and it is a different claim about history; change the CAUSE and it is a
      different claim about history too, which is why parentId sits inside the comparison. An id
      that is not a UUID at all never gets that far: on this route the id IS the idempotency key,
      and a caller that cannot spell one has no retry-safe identity to offer.

      A misspelled `order` is refused for the reason the parameter exists: answering it with the
      opposite direction would hand a catch-up consumer the head of the log and let it record a
      watermark it never reached.

      And the doors, which only a deployed process has. Publishing is the machine role's; a
      caller the edge never named is challenged at the socket's handshake and at the write alike,
      and a person's session — named, trusted, holding the role that reads every page of the
      events client — still cannot put anything into the log. The log is written by services and
      read by people, and getting that the wrong way round would let a browser forge the
      platform's history.
      """)
  void aReusedIdIsRefusedAndOnlyAMachineWrites(Interactions story) {
    String id = UUID.randomUUID().toString();
    String envelope = envelope(CONTESTED, OCCURRED_AT, PAYLOAD);

    // The publisher's first, honest attempt — the row this story then contests.
    publisher().body(envelope).when().put(EVENTS + "/" + id).then().statusCode(201);

    // (a) same id, different bytes. A reused UUID: 400, and the message says so rather than leaving
    // an outbox to guess.
    publisher()
        .body(envelope(CONTESTED, OCCURRED_AT, OTHER_PAYLOAD))
        .when()
        .put(EVENTS + "/" + id)
        .then()
        .statusCode(400)
        .body("message", containsString("may not be reused"));
    story
        .happened(
            "a publisher's outbox",
            "qits-events",
            "PUT /events/api/events/{id} (a known id, different payload) -> 400")
        .as("contested-payload-refused");

    // (b) the cause is inside the comparison too, and it is the sharper case: a publisher that
    // learned about causation between two attempts is claiming a different shape of history for one
    // id, and a server that kept the first quietly would leave two services disagreeing about it
    // with no error anywhere.
    publisher()
        .body(envelope(CONTESTED, OCCURRED_AT, PAYLOAD, UUID.randomUUID().toString()))
        .when()
        .put(EVENTS + "/" + id)
        .then()
        .statusCode(400);
    story
        .happened(
            "a publisher's outbox",
            "qits-events",
            "PUT /events/api/events/{id} (a known id, a different parent) -> 400")
        .as("contested-parent-refused");

    // (c) an id that is not a UUID. Refused before any lookup: on PUT the id IS the idempotency
    // key.
    publisher().body(envelope).when().put(EVENTS + "/not-a-uuid").then().statusCode(400);
    story
        .happened(
            "a publisher's outbox",
            "qits-events",
            "PUT /events/api/events/not-a-uuid -> 400 (the id is the idempotency key)")
        .as("unspellable-id-refused");

    // (d) the read side's one refusal, and it belongs to the consumer of the story above: `order`
    // is a parameter this service defined, so a misspelling is a client error. Falling back to
    // descending would answer a catch-up read with the HEAD of the log.
    consumer()
        .queryParam("name", CONTESTED)
        .queryParam("order", "sideways")
        .when()
        .get(EVENTS)
        .then()
        .statusCode(400)
        .body("message", containsString("order"));
    story
        .happened(
            "a durable consumer",
            "qits-events",
            "GET /events/api/events?order=sideways -> 400 (never the opposite direction)")
        .as("misspelled-order-refused");

    // --- the doors. Unreachable from any @QuarkusTest here: the %test dev-user hands every request
    // all four platform roles, and it is LaunchMode-guarded away in this process.

    // No header at all: ForwardAuthMechanism yields no identity, so @RolesAllowed challenges.
    given()
        .contentType(ContentType.JSON)
        .body(envelope)
        .when()
        .put(EVENTS + "/" + UUID.randomUUID())
        .then()
        .statusCode(401);
    story
        .happened(
            "an unnamed caller",
            "qits-events",
            "PUT /events/api/events/{id} (no X-Qits-User) -> 401")
        .as("unnamed-publisher-challenged");

    // The same door on the stream, and it is the HANDSHAKE that holds it — class-level
    // @RolesAllowed on a websockets-next endpoint is checked on the HTTP upgrade, so an
    // unauthorised consumer is refused the connection rather than connected and then ignored. A
    // socket that upgraded and stayed silent would be indistinguishable from a bus with nothing to
    // say.
    assertThrows(
        Exception.class,
        () -> FakeSubscriber.dial(stream),
        "an unnamed subscriber must be refused the upgrade, not connected and ignored");
    story
        .happened(
            "an unnamed consumer",
            "qits-events",
            "WS /events/stream (no X-Qits-User) -> the handshake is refused")
        .as("unnamed-consumer-refused");

    // Named by the edge and holding the PERSON's role: authenticated, and still not a publisher.
    // 403 and not 401, because there is a caller and it is the grant that is missing — collapsing
    // the two would tell an operator to log in again for something logging in cannot fix.
    given()
        .header(USER_HEADER, "alice")
        .header(ROLES_HEADER, ADMIN_ROLE)
        .contentType(ContentType.JSON)
        .body(envelope)
        .when()
        .put(EVENTS + "/" + UUID.randomUUID())
        .then()
        .statusCode(403);
    story
        .happened(
            "a person's session",
            "qits-events",
            "PUT /events/api/events/{id} (X-Qits-Roles: qits:admin) -> 403")
        .as("person-may-not-publish");

    // …and the assertion that makes the three above mean something: the same person's session
    // READS the log perfectly well. A service that had simply been locked would pass all of them.
    given()
        .header(USER_HEADER, "alice")
        .header(ROLES_HEADER, ADMIN_ROLE)
        .queryParam("name", CONTESTED)
        .when()
        .get(EVENTS)
        .then()
        .statusCode(200)
        .body("events.id", hasItem(id));
    story
        .happened(
            "a person's session",
            "qits-events",
            "GET /events/api/events?name=… (X-Qits-Roles: qits:admin) -> 200")
        .as("person-still-reads");
  }

  // --- the two identities, as the edge presents them -------------------------------------------

  /** A sibling service's outbox: the machine role, and a JSON body on every write. */
  private static RequestSpecification publisher() {
    return given()
        .header(USER_HEADER, PUBLISHER)
        .header(ROLES_HEADER, SYSTEM_ROLE)
        .contentType(ContentType.JSON);
  }

  /** The same service reading its own catch-up — the list route takes the machine role too. */
  private static RequestSpecification consumer() {
    return given().header(USER_HEADER, PUBLISHER).header(ROLES_HEADER, SYSTEM_ROLE);
  }

  /** The headers that identity travels as on an UPGRADE, which is where the socket's door is. */
  private static Map<String, String> machineCredential() {
    return Map.of(USER_HEADER, PUBLISHER, ROLES_HEADER, SYSTEM_ROLE);
  }

  // --- envelopes --------------------------------------------------------------------------------

  /**
   * The publish envelope, assembled as TEXT rather than from an object, because what is under test
   * includes the wire: {@code payload} is canonical JSON inside a string and reaches the server
   * escaped exactly as a publisher escapes it.
   */
  private static String envelope(String name, String occurredAt, String payload) {
    return envelope(name, occurredAt, payload, null);
  }

  /**
   * The same envelope, optionally naming the event that caused this one. A null parent is left off
   * the wire entirely rather than sent as {@code null}: absent-means-null is the contract's one
   * backward-compatibility clause — the bytes an older publisher sends — and it is the shape most
   * of the fleet still publishes in.
   */
  private static String envelope(
      String name, String occurredAt, String payload, String parentId) {
    return "{\"name\":\""
        + name
        + "\",\"occurredAt\":\""
        + occurredAt
        + "\",\"payload\":\""
        + payload.replace("\\", "\\\\").replace("\"", "\\\"")
        + "\",\"description\":null"
        + (parentId == null ? "" : ",\"parentId\":\"" + parentId + "\"")
        + ",\"environment\":\"platform\"}";
  }

  // --- the socket, waited on -------------------------------------------------------------------

  /**
   * Publish handshake events until one comes back, which is the only way a client of this protocol
   * learns its subscription took: {@code {"subscribe": [...]}} is answered with nothing at all, and
   * a publish that raced it would be delivered to nobody and look like a broken fan-out.
   *
   * <p>Each attempt is its own UUID and therefore its own CREATE, because only a create
   * broadcasts — re-PUTting one id would be a replay after the first attempt and would push
   * nothing however long this waited.
   */
  private static void awaitSubscriptionIsLive(FakeSubscriber consumer) throws Exception {
    String envelope = envelope(HANDSHAKE, HANDSHAKE_AT, PAYLOAD);
    long deadline = System.nanoTime() + ARRIVAL.toNanos();
    while (System.nanoTime() < deadline) {
      publisher()
          .body(envelope)
          .when()
          .put(EVENTS + "/" + UUID.randomUUID())
          .then()
          .statusCode(201);
      if (consumer.next(Duration.ofSeconds(2)) != null) {
        return;
      }
    }
    throw new AssertionError(
        "the packaged artifact never pushed a frame for the handshake signature — the subscription"
            + " never took, or the fan-out is not running");
  }

  /** The next frame naming {@code signature}, skipping whatever else is still in flight. */
  private static String awaitFrame(FakeSubscriber consumer, String signature) throws Exception {
    long deadline = System.nanoTime() + ARRIVAL.toNanos();
    while (System.nanoTime() < deadline) {
      String frame = consumer.next(Duration.ofSeconds(2));
      if (frame != null && frame.contains("\"name\":\"" + signature + "\"")) {
        return frame;
      }
    }
    return null;
  }

  /** Empty the queue, so a later silence is the server's and not a leftover's. */
  private static void drain(FakeSubscriber consumer) throws Exception {
    while (consumer.next(Duration.ofMillis(500)) != null) {
      // nothing to do: the frames of the warm-up above are not this story's business
    }
  }

  @AfterAll
  static void bothStoryReportsAreComplete() {
    // The extension emits each report in its afterEach, so both are on disk before @AfterAll runs.
    ReportAssertions.assertComplete(CATEGORY, DELIVERY_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertInteraction(
        CATEGORY,
        DELIVERY_SLUG,
        "qits-events",
        "a durable consumer",
        "the EventCreated frame, live");
    ReportAssertions.assertStepId(CATEGORY, DELIVERY_SLUG, "subscribed");
    ReportAssertions.assertStepId(CATEGORY, DELIVERY_SLUG, "published");
    ReportAssertions.assertStepId(CATEGORY, DELIVERY_SLUG, "delivered");
    ReportAssertions.assertStepId(CATEGORY, DELIVERY_SLUG, "replayed-silently");
    ReportAssertions.assertStepId(CATEGORY, DELIVERY_SLUG, "watermark-taken");
    ReportAssertions.assertStepId(CATEGORY, DELIVERY_SLUG, "caught-up");
    ReportAssertions.assertStepId(CATEGORY, DELIVERY_SLUG, "log-holds-it-once");

    ReportAssertions.assertComplete(CATEGORY, REFUSAL_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, REFUSAL_SLUG, "contested-payload-refused");
    ReportAssertions.assertStepId(CATEGORY, REFUSAL_SLUG, "contested-parent-refused");
    ReportAssertions.assertStepId(CATEGORY, REFUSAL_SLUG, "unspellable-id-refused");
    ReportAssertions.assertStepId(CATEGORY, REFUSAL_SLUG, "misspelled-order-refused");
    ReportAssertions.assertStepId(CATEGORY, REFUSAL_SLUG, "unnamed-publisher-challenged");
    ReportAssertions.assertStepId(CATEGORY, REFUSAL_SLUG, "unnamed-consumer-refused");
    ReportAssertions.assertStepId(CATEGORY, REFUSAL_SLUG, "person-may-not-publish");
    ReportAssertions.assertStepId(CATEGORY, REFUSAL_SLUG, "person-still-reads");
  }
}
