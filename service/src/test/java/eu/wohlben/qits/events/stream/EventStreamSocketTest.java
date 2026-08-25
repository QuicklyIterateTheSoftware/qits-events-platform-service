package eu.wohlben.qits.events.stream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The stream protocol, driven by a real WebSocket from {@link FakeSubscriber} against the real
 * endpoint — the qits-ci {@code CiDaemonSocketTest} arrangement, kept: the server cannot tell this
 * client from qits-ci's eventsourcing module, so subscribe, match and fan-out are all provable with
 * nothing on PATH, and only "the route survived being packaged" is left to {@code
 * PackagedSurfaceIT}.
 *
 * <p>Addressed through {@code @TestHTTPResource} at its <b>absolute</b> path: {@code /events/stream}
 * is a literal that does not follow {@code quarkus.rest.path}, and it is the address the publisher's
 * config derives, so a relative address here would not catch a segment regression.
 */
@QuarkusTest
class EventStreamSocketTest {

  /** Generous — it is only ever spent when something is actually broken. */
  private static final Duration SOON = Duration.ofSeconds(10);

  /**
   * Short, and spent in full on every "nothing arrives" assertion. The push is in flight before the
   * write that caused it has answered, so a frame that has not landed within this is not late.
   */
  private static final Duration BRIEFLY = Duration.ofMillis(500);

  private static final ObjectMapper JSON = new ObjectMapper();

  @TestHTTPResource("/events/stream")
  URI endpoint;

  @Inject EventStreamSubscriptions subscriptions;

  /** Distinct per test, so one class's traffic is never another's. */
  private static String aSignature(String label) {
    return "Probe" + label + System.nanoTime();
  }

  /** A JSON document embedded in a JSON string field, which is what the payload contract is. */
  private static String quoted(String payload) {
    return payload == null
        ? "null"
        : "\"" + payload.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  private static String record(String name, String payload) {
    return given()
        .contentType(ContentType.JSON)
        .body(
            "{\"name\":\""
                + name
                + "\",\"occurredAt\":\"2026-07-31T12:46:03Z\",\"payload\":"
                + quoted(payload)
                + ",\"description\":\"recorded by the suite\"}")
        .when()
        .post("/events/api/events")
        .then()
        .statusCode(200)
        .extract()
        .path("event.id");
  }

  private static io.restassured.response.Response publish(String id, String name, String payload) {
    return publish(id, name, payload, null);
  }

  private static io.restassured.response.Response publish(
      String id, String name, String payload, String parentId) {
    return publish(id, name, payload, parentId, null);
  }

  private static io.restassured.response.Response publish(
      String id, String name, String payload, String parentId, String environment) {
    return given()
        .contentType(ContentType.JSON)
        .body(
            "{\"name\":\""
                + name
                + "\",\"occurredAt\":\"2026-07-31T12:46:03Z\",\"payload\":"
                + quoted(payload)
                + ",\"description\":null,\"parentId\":"
                + (parentId == null ? "null" : "\"" + parentId + "\"")
                + ",\"environment\":"
                + (environment == null ? "null" : "\"" + environment + "\"")
                + "}")
        .when()
        .put("/events/api/events/" + id);
  }

  /** The next frame, as JSON, or a failure naming the fan-out rather than a NullPointerException. */
  private static JsonNode nextFrame(FakeSubscriber subscriber) throws Exception {
    String frame = subscriber.next(SOON);
    assertNotNull(frame, "no frame arrived");
    return JSON.readTree(frame);
  }

  /**
   * Wait until the server has actually applied a subscribe frame. The protocol has no ack, so
   * without this a create could beat the frame and the push would be lost rather than late — a flake
   * that would look like a broken fan-out.
   */
  private void awaitSubscribed(String name) throws InterruptedException {
    long deadline = System.nanoTime() + SOON.toNanos();
    while (subscriptions.subscriberCountFor(name) == 0) {
      assertTrue(System.nanoTime() < deadline, "the server never applied the subscribe frame");
      Thread.sleep(20);
    }
  }

  @Test
  void aSubscriberIsPushedWhatItNamedAndNothingElse() throws Exception {
    String wanted = aSignature("Wanted");
    String unwanted = aSignature("Unwanted");
    try (FakeSubscriber subscriber = FakeSubscriber.dial(endpoint)) {
      subscriber.subscribe(wanted);
      awaitSubscribed(wanted);

      record(wanted, "{\"a\":1}");
      assertEquals(wanted, nextFrame(subscriber).get("name").asText());

      record(unwanted, null);
      assertNull(
          subscriber.next(BRIEFLY),
          "an event whose name nobody asked for must reach nobody");
    }
  }

  @Test
  void theFrameIsTheEnvelopeAndCarriesNoRowBookkeeping() throws Exception {
    String name = aSignature("Envelope");
    try (FakeSubscriber subscriber = FakeSubscriber.dial(endpoint)) {
      subscriber.subscribe(name);
      awaitSubscribed(name);

      String id = record(name, "{\"branch\":\"main\"}");
      JsonNode frame = nextFrame(subscriber);

      // The seven fields of the wire contract, and NOT createdAt/updatedAt: those are this
      // database's bookkeeping, not facts about the thing that happened. Their ORDER is no longer
      // part of the contract — both sides bind by name — but the set is, and so is the fact that
      // each new field was APPENDED: a subscriber built against the first five reads the frame it
      // always read.
      List<String> fields = new ArrayList<>();
      frame.fieldNames().forEachRemaining(fields::add);
      assertEquals(
          List.of("id", "name", "occurredAt", "payload", "description", "parentId", "environment"),
          fields);

      assertEquals(id, frame.get("id").asText());
      assertEquals(name, frame.get("name").asText());
      assertEquals("2026-07-31T12:46:03Z", frame.get("occurredAt").asText());
      // The payload arrives as the STRING it was stored as, still carrying the publisher's own
      // canonical JSON — not re-parsed into an object and not reformatted.
      assertEquals("{\"branch\":\"main\"}", frame.get("payload").asText());
      assertEquals("recorded by the suite", frame.get("description").asText());
      // An explicit null, not an omission: a subscriber reads "this event is a root" off the frame
      // rather than off a missing key, which is the only reading that stays true when it is set.
      assertTrue(frame.get("parentId").isNull());
      // Same clause for the tier: null is "recorded before the platform knew tiers", and it is on
      // the wire as a value rather than a gap.
      assertTrue(frame.get("environment").isNull());
    }
  }

  @Test
  void aFrameCarriesTheCauseTheEventWasPublishedUnder() throws Exception {
    // The whole point of the field being on the frame rather than only on the read model: a
    // subscriber drawing a release train sees the edge as the event arrives, with nothing to poll.
    String name = aSignature("Caused");
    String parent = UUID.randomUUID().toString();
    String id = UUID.randomUUID().toString();
    try (FakeSubscriber subscriber = FakeSubscriber.dial(endpoint)) {
      subscriber.subscribe(name);
      awaitSubscribed(name);

      publish(id, name, "{\"attempt\":1}", parent).then().statusCode(201);
      JsonNode frame = nextFrame(subscriber);
      assertEquals(id, frame.get("id").asText());
      assertEquals(parent, frame.get("parentId").asText());
      // The parent is an id this log has never seen, and the frame carries it all the same —
      // nothing orders a parent's arrival before its child's.
    }
  }

  @Test
  void aFrameCarriesTheTierTheEventWasPublishedFrom() throws Exception {
    // Since the bus became one instance for every environment, the frame is where a subscriber
    // learns which tier an event belongs to — there is no per-tier broker left to encode it.
    String name = aSignature("Tiered");
    String id = UUID.randomUUID().toString();
    try (FakeSubscriber subscriber = FakeSubscriber.dial(endpoint)) {
      subscriber.subscribe(name);
      awaitSubscribed(name);

      publish(id, name, "{\"attempt\":1}", null, "dev").then().statusCode(201);
      JsonNode frame = nextFrame(subscriber);
      assertEquals(id, frame.get("id").asText());
      assertEquals("dev", frame.get("environment").asText());
    }
  }

  @Test
  void theWildcardMeansEverything() throws Exception {
    String first = aSignature("StarOne");
    String second = aSignature("StarTwo");
    try (FakeSubscriber subscriber = FakeSubscriber.dial(endpoint)) {
      subscriber.subscribe("*");
      awaitSubscribed(first);

      record(first, null);
      record(second, null);

      List<String> names = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        names.add(nextFrame(subscriber).get("name").asText());
      }
      assertEquals(List.of(first, second), names);
    }
  }

  @Test
  void aConnectionThatHasNotSubscribedIsPushedNothing() throws Exception {
    // Silence is the honest default: an open socket is not a statement of interest, and a browser
    // tab that merely connected should not be handed the whole log.
    String name = aSignature("Silent");
    try (FakeSubscriber subscriber = FakeSubscriber.dial(endpoint)) {
      record(name, null);
      assertNull(subscriber.next(BRIEFLY));
    }
  }

  @Test
  void aSubscribeFrameReplacesTheSetRatherThanGrowingIt() throws Exception {
    String dropped = aSignature("Dropped");
    String kept = aSignature("Kept");
    try (FakeSubscriber subscriber = FakeSubscriber.dial(endpoint)) {
      subscriber.subscribe(dropped);
      awaitSubscribed(dropped);
      subscriber.subscribe(kept);
      awaitSubscribed(kept);

      record(dropped, null);
      assertNull(
          subscriber.next(BRIEFLY),
          "a client that stopped naming a signature has stopped asking for it");

      record(kept, null);
      assertEquals(kept, nextFrame(subscriber).get("name").asText());
    }
  }

  @Test
  void twoConnectionsEachGetOnlyWhatTheyAskedFor() throws Exception {
    String mine = aSignature("Mine");
    String yours = aSignature("Yours");
    try (FakeSubscriber one = FakeSubscriber.dial(endpoint);
        FakeSubscriber two = FakeSubscriber.dial(endpoint)) {
      one.subscribe(mine);
      two.subscribe(yours);
      awaitSubscribed(mine);
      awaitSubscribed(yours);

      record(mine, null);
      assertEquals(mine, nextFrame(one).get("name").asText());
      assertNull(two.next(BRIEFLY), "the fan-out is per connection, not per process");
    }
  }

  @Test
  void aPublishPushesOnCreateAndAReplayPushesNothing() throws Exception {
    // The property the whole retry story rests on: a publisher that lost the answer to its first
    // attempt sends the same bytes again, and a subscriber must not see the event twice for it.
    String name = aSignature("Published");
    String id = UUID.randomUUID().toString();
    try (FakeSubscriber subscriber = FakeSubscriber.dial(endpoint)) {
      subscriber.subscribe(name);
      awaitSubscribed(name);

      publish(id, name, "{\"attempt\":1}").then().statusCode(201);
      assertEquals(id, nextFrame(subscriber).get("id").asText());

      publish(id, name, "{\"attempt\":1}").then().statusCode(200);
      assertNull(subscriber.next(BRIEFLY), "a 200 replay must push nothing");
    }
  }

  @Test
  void anUnreadableFrameCostsTheFrameAndNotTheConnection() throws Exception {
    String name = aSignature("Garbage");
    try (FakeSubscriber subscriber = FakeSubscriber.dial(endpoint)) {
      subscriber.sendRaw("not json at all");
      subscriber.sendRaw("{\"subscribe\":\"a string, not an array\"}");
      subscriber.sendRaw("{\"somethingElse\":[\"x\"]}");
      subscriber.sendRaw("[]");

      // Still speaking, and still able to subscribe: the connection survived all four.
      subscriber.subscribe(name);
      awaitSubscribed(name);
      record(name, null);
      assertEquals(name, nextFrame(subscriber).get("name").asText());
      assertTrue(subscriber.isOpen());
    }
  }

  @Test
  void aClosedConnectionLeavesNoSubscriptionBehind() throws Exception {
    String name = aSignature("Gone");
    try (FakeSubscriber subscriber = FakeSubscriber.dial(endpoint)) {
      subscriber.subscribe(name);
      awaitSubscribed(name);
    }
    long deadline = System.nanoTime() + SOON.toNanos();
    while (subscriptions.subscriberCountFor(name) != 0) {
      assertTrue(System.nanoTime() < deadline, "a closed connection stayed in the fan-out table");
      Thread.sleep(20);
    }
    // And the write path is entirely unbothered by the departure.
    record(name, null);
  }
}
