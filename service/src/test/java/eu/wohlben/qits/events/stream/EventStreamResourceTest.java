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
 * The Server-Sent Events transport, driven by a real HTTP reader ({@link SseReader}) against the
 * real route — the arrangement {@link EventStreamSocketTest} uses for the websocket, kept, because
 * the two transports are two spellings of one fan-out and their proofs should read the same.
 *
 * <p>What is <b>not</b> re-proved here: matching, replacement semantics, the {@code AFTER_SUCCESS}
 * rule, "a replay pushes nothing". Those belong to {@code EventStreamSubscriptions} and the socket
 * suite already exercises them through it. What this class owes is the part that is genuinely new —
 * the URL as the subscribe frame, the SSE framing (the {@code id:} field and the raw envelope in
 * {@code data:}), and the connect/disconnect lifecycle of a reader that cannot speak.
 *
 * <p>Addressed through {@code @TestHTTPResource} at its <b>absolute</b> path. Unlike the socket's
 * literal this one is derived — {@code quarkus.rest.path} plus {@code @Path("/stream")} — and
 * spelling the whole of it here is what would catch either half moving.
 */
@QuarkusTest
class EventStreamResourceTest {

  /** Generous — it is only ever spent when something is actually broken. */
  private static final Duration SOON = Duration.ofSeconds(10);

  /**
   * Short, and spent in full on every "nothing arrives" assertion. The push is in flight before the
   * write that caused it has answered, so a frame that has not landed within this is not late.
   */
  private static final Duration BRIEFLY = Duration.ofMillis(500);

  private static final ObjectMapper JSON = new ObjectMapper();

  @TestHTTPResource("/events/api/stream")
  URI endpoint;

  @Inject EventStreamSubscriptions subscriptions;

  /** Distinct per test, so one class's traffic is never another's. */
  private static String aSignature(String label) {
    return "Sse" + label + System.nanoTime();
  }

  private URI withNames(String names) {
    return names == null
        ? endpoint
        : URI.create(endpoint + "?names=" + names.replace("*", "%2A").replace(",", "%2C"));
  }

  /** The bus's own publish route, which is what a frame has to come out of to prove anything. */
  private static String publish(String name) {
    String id = UUID.randomUUID().toString();
    given()
        .contentType(ContentType.JSON)
        .body(
            "{\"name\":\""
                + name
                + "\",\"occurredAt\":\"2026-07-31T12:46:03Z\",\"payload\":\"{\\\"branch\\\":\\\"main\\\"}\"}")
        .when()
        .put("/events/api/events/" + id)
        .then()
        .statusCode(201);
    return id;
  }

  /**
   * Wait until the server has actually registered this reader's interest. The {@code Multi} is
   * subscribed after the resource method returns, so without this a publish could beat the
   * registration and the push would be lost rather than late — a flake that would look like a broken
   * fan-out.
   */
  private void awaitSubscribed(String name) throws InterruptedException {
    long deadline = System.nanoTime() + SOON.toNanos();
    while (subscriptions.subscriberCountFor(name) == 0) {
      assertTrue(System.nanoTime() < deadline, "the server never registered the SSE reader");
      Thread.sleep(20);
    }
  }

  @Test
  void aReaderThatNamedNothingIsConnectedAndToldNothing() throws Exception {
    // Both halves matter and they pull in opposite directions. A stream that answered nothing at all
    // would also pass "no events arrived" — so the connection has to be observably OPEN first, which
    // is what the opening comment is for, and only then is the silence a statement about the bus.
    String name = aSignature("Silent");
    try (SseReader reader = SseReader.open(endpoint)) {
      assertEquals(200, reader.status());
      assertTrue(
          reader.contentType().startsWith("text/event-stream"),
          "an SSE route must answer as one; got: " + reader.contentType());
      assertNotNull(reader.nextLine(SOON), "the response head was flushed but no comment followed");

      publish(name);
      assertNull(
          reader.nextFrame(BRIEFLY),
          "a reader that has not said what it wants must be told nothing");
    }
  }

  @Test
  void aReaderIsPushedWhatItNamedAndNothingElse() throws Exception {
    String wanted = aSignature("Wanted");
    String unwanted = aSignature("Unwanted");
    try (SseReader reader = SseReader.open(withNames(wanted))) {
      awaitSubscribed(wanted);

      String id = publish(wanted);
      SseReader.Frame frame = reader.nextFrame(SOON);
      assertNotNull(frame, "no frame arrived");
      assertEquals(wanted, JSON.readTree(frame.data()).get("name").asText());
      assertEquals(id, JSON.readTree(frame.data()).get("id").asText());

      publish(unwanted);
      assertNull(
          reader.nextFrame(BRIEFLY), "an event whose name nobody asked for must reach nobody");
    }
  }

  @Test
  void theWildcardMeansEverything() throws Exception {
    String first = aSignature("StarOne");
    String second = aSignature("StarTwo");
    try (SseReader reader = SseReader.open(withNames("*"))) {
      awaitSubscribed(first);

      publish(first);
      publish(second);

      List<String> names = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        SseReader.Frame frame = reader.nextFrame(SOON);
        assertNotNull(frame, "only " + names.size() + " of two frames arrived");
        names.add(JSON.readTree(frame.data()).get("name").asText());
      }
      assertEquals(List.of(first, second), names);
    }
  }

  @Test
  void theSseIdFieldCarriesTheEventId() throws Exception {
    // The one field of the SSE envelope this transport fills, and the reason it is filled: a browser
    // hands it back as Last-Event-ID, and catch-up — when it exists — resumes the log from it.
    String name = aSignature("Identified");
    try (SseReader reader = SseReader.open(withNames(name))) {
      awaitSubscribed(name);

      String id = publish(name);
      SseReader.Frame frame = reader.nextFrame(SOON);
      assertNotNull(frame, "no frame arrived");
      assertEquals(id, frame.id(), "the SSE id: field must be the event's own id");
    }
  }

  @Test
  void theDataIsTheSameEnvelopeTheSocketPushes() throws Exception {
    // Not "some JSON describing the event" — the SAME bytes, from the same ObjectMapper call, with
    // the same seven fields in the same appended order and the same EXPLICIT nulls. A reader that
    // switched transports must not have to learn a second shape, and an omit-nulls customizer here
    // would be as silent a break as it is on the socket.
    String name = aSignature("Envelope");
    try (SseReader reader = SseReader.open(withNames(name))) {
      awaitSubscribed(name);

      String id = publish(name);
      SseReader.Frame frame = reader.nextFrame(SOON);
      assertNotNull(frame, "no frame arrived");

      // The raw data line is the envelope itself, not a JSON string containing it: a media type on
      // the SSE event would have routed it through Jackson and delivered it quoted and escaped.
      assertTrue(frame.data().startsWith("{"), "data must be the envelope: " + frame.data());
      assertTrue(frame.data().contains("\"parentId\":null"), frame.data());
      assertTrue(frame.data().contains("\"environment\":null"), frame.data());

      JsonNode envelope = JSON.readTree(frame.data());
      List<String> fields = new ArrayList<>();
      envelope.fieldNames().forEachRemaining(fields::add);
      assertEquals(
          List.of("id", "name", "occurredAt", "payload", "description", "parentId", "environment"),
          fields);
      assertEquals(id, envelope.get("id").asText());
      assertEquals("2026-07-31T12:46:03Z", envelope.get("occurredAt").asText());
      assertEquals("{\"branch\":\"main\"}", envelope.get("payload").asText());
      assertTrue(envelope.get("parentId").isNull());
      assertTrue(envelope.get("environment").isNull());
    }
  }

  @Test
  void blankNamesAreIgnoredRatherThanRefused() throws Exception {
    // `?names=,,Foo,` is a client that built its query badly, not a client asking for a signature
    // called "". There is no interest a blank string could express, and refusing the connect would
    // tell an EventSource nothing it could act on — it retries an error forever.
    String name = aSignature("Blanks");
    try (SseReader reader = SseReader.open(withNames(",," + name + ","))) {
      assertEquals(200, reader.status());
      awaitSubscribed(name);

      String id = publish(name);
      SseReader.Frame frame = reader.nextFrame(SOON);
      assertNotNull(frame, "no frame arrived");
      assertEquals(id, frame.id());
    }
  }

  @Test
  void twoReadersEachGetOnlyWhatTheyAskedFor() throws Exception {
    String mine = aSignature("Mine");
    String yours = aSignature("Yours");
    try (SseReader one = SseReader.open(withNames(mine));
        SseReader two = SseReader.open(withNames(yours))) {
      awaitSubscribed(mine);
      awaitSubscribed(yours);

      publish(mine);
      assertNotNull(one.nextFrame(SOON));
      assertNull(two.nextFrame(BRIEFLY), "the fan-out is per connection, not per process");
    }
  }

  @Test
  void aClosedReaderLeavesNoSubscriptionBehind() throws Exception {
    // The only close this transport has: the client goes away, the response's Multi is cancelled,
    // and the termination handler is what takes the sink out of the table. Without it a closed
    // browser tab would be pushed to forever.
    String name = aSignature("Gone");
    try (SseReader reader = SseReader.open(withNames(name))) {
      awaitSubscribed(name);
      assertEquals(1, subscriptions.subscriberCountFor(name));
    }
    long deadline = System.nanoTime() + SOON.toNanos();
    while (subscriptions.subscriberCountFor(name) != 0) {
      assertTrue(System.nanoTime() < deadline, "a closed reader stayed in the fan-out table");
      Thread.sleep(20);
    }
    // And the write path is entirely unbothered by the departure.
    publish(name);
  }
}
