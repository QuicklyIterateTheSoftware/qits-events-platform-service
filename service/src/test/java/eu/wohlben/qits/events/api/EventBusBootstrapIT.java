package eu.wohlben.qits.events.api;

import static eu.wohlben.qits.events.stories.support.StoryTarget.EVENTS;
import static eu.wohlben.qits.events.stories.support.StoryTarget.SERVICE;
import static eu.wohlben.qits.events.stories.support.StoryTarget.STORE;
import static eu.wohlben.qits.events.stories.support.StoryTarget.STREAM;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.events.stories.support.StoryNetwork;
import eu.wohlben.qits.events.stories.support.StoryProfile;
import eu.wohlben.qits.events.stories.support.StoryStream;
import eu.wohlben.qits.events.stories.support.StoryTarget;
import eu.wohlben.qits.events.stream.FakeSubscriber;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.Network;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The bus as it is <b>packaged</b> — the fast-jar under {@code ./mvnw verify -DskipITs=false}, the
 * GraalVM binary under {@code -Dnative} — telling the one story this repository exists for: an event
 * goes in once, comes out live, and is still there to be read afterwards by a consumer that was not
 * listening at the time. Everything else in {@code stories/} is a reading of some part of that.
 *
 * <p>{@code PackagedSurfaceIT} beside it already probes the artifact's <em>surface</em> — the client
 * at the root, the prefixes SPA routing must not swallow, a socket that upgrades. What it does not do
 * is tell a story: it is a list of properties, each true on its own, and the thing qits-events
 * actually promises the fleet is the order they happen in. That order is what is here.
 *
 * <p><b>This class is the catalogue's oldest and not its owner.</b> It shares {@link StoryProfile}
 * with every story class under {@code eu.wohlben.qits.events.stories}, which is what makes them one
 * launched process, one database and — the part that matters most on this service — <b>one in-memory
 * subscription registry</b>: the fan-out table lives in the process, so a second profile would be a
 * second bus, and a subscriber connected to one would be invisible to a publish that reached the
 * other. Its {@code @TestMethodOrder} is for reproducibility of the emitted reports only: every edge
 * in this catalogue is observed as it happens, so nothing depends on which story drains first.
 *
 * <p>The two taps, the label rules, the actor discipline and why {@code assertNoEdgesFrom(SERVICE)}
 * appears nowhere in this catalogue are all documented once, on {@link StoryTarget} and {@link
 * StoryNetwork}. Read those before adding a story anywhere.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class EventBusBootstrapIT {

  static final String CATEGORY = "event-bus";

  static final String DELIVERY_SLUG =
      "an-event-published-is-an-event-delivered-and-read-again-later";

  static final String REFUSAL_SLUG =
      "a-publisher-may-repeat-itself-never-contradict-itself-and-a-stranger-publishes-nothing";

  /**
   * The signature the consumer subscribes to first, and the one the warm-up publishes under.
   *
   * <p>It is a story beat rather than test scaffolding — see {@link StoryStream}: the protocol has no
   * acknowledgement, so the only way a consumer learns its subscription is live is by receiving
   * something.
   */
  static final String HANDSHAKE = "UserflowSubscriberHandshake";

  /** The event the delivery story is about. */
  static final String SIGNATURE = "UserflowBuildSucceeded";

  /**
   * The event the refusal story contests. It has a name of its own, so the two stories can never
   * read each other's rows — the discipline every class in this catalogue keeps.
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

  /** Canonical JSON <em>in a string</em> — see {@link StoryTarget#envelope}. */
  private static final String PAYLOAD = "{\"branch\":\"main\",\"repository\":\"qits-events\"}";

  /** What a contested retry sends the second time. One byte of difference is the whole test. */
  private static final String OTHER_PAYLOAD =
      "{\"branch\":\"somebody-elses\",\"repository\":\"qits-events\"}";

  /**
   * Every id these two stories minted. None of them may appear anywhere in either bundle: a note
   * never interpolates one (a note enters the definition hash) and every label scrubs one, so a leak
   * is exactly the symptom of a hash that will never settle.
   */
  private static final List<String> NEVER_IN_THE_BUNDLE = new ArrayList<>();

  /** The socket's absolute literal, resolved against the launched process's own port. */
  @TestHTTPResource(STREAM)
  URI stream;

  @BeforeAll
  static void tapTheHttpHalfOfTheBus() {
    StoryNetwork.install();
  }

  @UserStory(
      value = "An event published is an event delivered, and read again later",
      category = CATEGORY)
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
  @Order(1)
  void anEventIsDeliveredLiveRetriedSafelyAndFoundAgainOnCatchUp(Interactions story, Network net)
      throws Exception {
    // The one dependency this service has, and the reason every claim below is durable rather than
    // in-flight. No tap can see it — it is the datasource's own connection — so it is DECLARED, and
    // the renderers draw it dashed so a claim never reads like evidence.
    net.declare(
        NetworkEdge.JDBC, SERVICE, STORE, "insert one row, then page it ascending from a watermark");

    story.note("qits-events starts as the platform's one bus, its log on the provisioned postgres");
    given().get(StoryTarget.READY).then().statusCode(200).body("status", equalTo("UP"));

    String id = UUID.randomUUID().toString();
    NEVER_IN_THE_BUNDLE.add(id);
    String envelope = StoryTarget.envelope(SIGNATURE, OCCURRED_AT, PAYLOAD);

    // The actor is named BEFORE the dial: the socket tap reads it once, when the connection is
    // made, and keeps it for every frame that arrives afterwards on a thread this story does not
    // control. The framework resets it to a default at every story start, so nothing leaks in.
    NetworkCapture.actor(StoryTarget.DURABLE_CONSUMER);
    try (FakeSubscriber consumer =
        FakeSubscriber.dial(stream, StoryTarget.machineCredential())) {
      // The subscription replaces the connection's set and covers both names at once — the
      // handshake the consumer proves itself live with, and the signature it is here for. A
      // connection that has named nothing is subscribed to nothing, which is why this comes first.
      consumer.subscribe(HANDSHAKE, SIGNATURE);
      // The warm-up publishes as the OUTBOX does, so the actor moves before it — and stays there
      // for the story's own publish and its retry, which are the same caller.
      NetworkCapture.actor(StoryTarget.OUTBOX);
      StoryStream.awaitSubscriptionIsLive(consumer, HANDSHAKE, HANDSHAKE_AT, PAYLOAD);
      story
          .note(
              "the consumer dials with the machine credential the edge asserts and names the two"
                  + " signatures it cares about; the protocol acknowledges nothing, so the only"
                  + " proof a subscription took is a frame arriving")
          .as("subscribed");

      // --- the publish. 201, because the id is one nothing has seen: the row was created AND
      // announced. The envelope carries the tier the publisher ran in, which is inside the replay
      // comparison below.
      StoryTarget.publisher()
          .body(envelope)
          .when()
          .put(StoryTarget.event(id))
          .then()
          .statusCode(201)
          .body("event.id", equalTo(id))
          .body("event.payload", equalTo(PAYLOAD))
          .body("event.environment", equalTo("platform"));
      story
          .note(
              "a publisher's outbox PUTs the event under a UUID IT chose — the id is the"
                  + " idempotency key, which is what makes a retry safe — and an id nothing has"
                  + " seen is a 201: the row was created AND announced")
          .as("published");

      // --- the delivery. The frame is EventCreated's own JSON, which IS the wire contract; the
      // fields asserted are the ones a consumer routes and deduplicates on.
      String frame = StoryStream.awaitFrame(consumer, SIGNATURE);
      assertNotNull(frame, "the packaged artifact's stream pushed nothing for a published event");
      assertTrue(frame.contains("\"id\":\"" + id + "\""), frame);
      assertTrue(frame.contains("\"name\":\"" + SIGNATURE + "\""), frame);
      assertTrue(frame.contains("\"payload\":\"" + PAYLOAD.replace("\"", "\\\"") + "\""), frame);
      // The push is its own edge and points the other way: qits-events initiated it. Every frame
      // this story received draws the same one arrow — the diagram says delivery happened, and the
      // assertions above say what was in it.
      story
          .note(
              "the consumer is pushed the EventCreated frame while the write is still warm, and it"
                  + " carries the id, the signature and the publisher's own bytes")
          .as("delivered");

      // Anything the warm-up left in flight is drained here, so that the silence asserted below is
      // the replay's silence and not an empty queue that happened to have been emptied already.
      StoryStream.drain(consumer);

      // --- the retry. Same id, same bytes: the publisher's first attempt, sent again because its
      // answer was lost. 200 rather than 201 — nothing written — and no frame, which is the clause
      // every subscriber on the platform depends on and the one a status code alone cannot show.
      StoryTarget.publisher()
          .body(envelope)
          .when()
          .put(StoryTarget.event(id))
          .then()
          .statusCode(200);
      StoryStream.assertSilent(
          consumer,
          "a replayed publish must push nothing — a subscriber may not see an event twice because"
              + " an acknowledgement was lost");
      // "…and nothing is pushed" is an ABSENCE. Here it is an assertion and a note; the diagram-level
      // form of the same claim needs a connection that is demonstrably live and receiving nothing,
      // which is what stories/silence/QuietBusIT is for.
      story
          .note(
              "the same id and the same bytes come back 200 instead of 201, nothing is written —"
                  + " and NOTHING IS PUSHED: a subscriber must not see an event twice because an"
                  + " acknowledgement went missing")
          .as("replayed-silently");
    }

    // --- catch-up. The socket is closed: the consumer restarted, and all it kept is a watermark.
    // Everything from here is the consumer's again, and it is reading over HTTP now rather than
    // listening — which is the same durable consumer wearing the other half of the contract.
    NetworkCapture.actor(StoryTarget.DURABLE_CONSUMER);
    story.note("the consumer is restarted and comes back holding only its watermark");

    // The watermark itself, taken the way a consumer takes one — the cursor beside the last row it
    // handled, reading FORWARD one row at a time over the vocabulary it subscribes to. `nextCursor`
    // being non-null is the only end-of-log signal there is, and a full page is not one.
    JsonPath handled =
        StoryTarget.consumer()
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
    NEVER_IN_THE_BUNDLE.add(alreadyHandled);
    // The three catch-up reads are one edge: same caller, same route, same 200. What tells them
    // apart is entirely in the query string — ?order=asc, ?cursor=…, ?limit= — which never reaches
    // a label, deliberately: a cursor is a run-local value and would move the story's networkHash
    // every run. The notes carry the reading.
    story
        .note(
            "the watermark is taken the way a consumer takes one: reading FORWARD, one row at a"
                + " time, over the vocabulary it subscribes to. A non-null nextCursor is the only"
                + " end-of-log signal there is")
        .as("watermark-taken");

    // Forward from it: the row it already handled is never handed out a second time, and the event
    // published while it was away is there — ONCE, which is the retry's 200 seen from the log's end
    // rather than from the publisher's.
    StoryTarget.consumer()
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
        .note(
            "forward from it: the row it already handled is never handed out twice, and the event"
                + " published while it was away is there")
        .as("caught-up");

    // …and the row it found is the publisher's own event, byte for byte, with the head of the log
    // named: `nextCursor` null is what tells a catch-up consumer it has arrived and may go back to
    // listening. Filtered to this story's signature, so `hasSize(1)` is also the statement that the
    // retry wrote no second row.
    StoryTarget.consumer()
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
        .note(
            "and it is there ONCE, with the publisher's own bytes and a null nextCursor naming the"
                + " head — the retry's 200 seen from the log's end rather than the publisher's."
                + " Live delivery and catch-up are two readings of one log")
        .as("log-holds-it-once");
  }

  @UserStory(
      value =
          "A publisher may repeat itself, never contradict itself — and a stranger publishes"
              + " nothing",
      category = CATEGORY)
  @UserStoryDescription(
      """
      The flip side of a bus whose write is safe to retry. Idempotency is only worth something
      if the server can tell a retry from a lie, and this is where that line is drawn — plus the
      doors, which decide who is allowed to draw near it at all.

      A UUID reused for different content is refused with 400 and not with a conflict to sit and
      poll on: no retry fixes a publisher that reused an id, so telling it to try again would be
      a wait with no end. The comparison covers the occurrence and not the prose about it —
      change the payload and it is a different claim about history; change the CAUSE and it is a
      different claim about history too, which is why parentId sits inside the comparison, and
      the TIER sits inside it for the same reason. An id that is not a UUID at all never gets
      that far: on this route the id IS the idempotency key, and a caller that cannot spell one
      has no retry-safe identity to offer. Nor may an event be its own cause.

      Two shapes are refused before any lookup, and both are refusals of things this service
      could never have stored: an environment that is not a dns-safe name, and — on the read
      side — a misspelled `order`, which is refused for the reason the parameter exists, because
      answering it with the opposite direction would hand a catch-up consumer the head of the log
      and let it record a watermark it never reached. A cursor that is not `<occurredAt>,<id>`
      goes the same way.

      And the doors, which only a deployed process has. Publishing is the machine role's; a
      caller the edge never named is challenged at the socket's handshake and at the write alike,
      and a person's session — named, trusted, holding the role that reads every page of the
      events client — still cannot put anything into the log. The log is written by services and
      read by people, and getting that the wrong way round would let a browser forge the
      platform's history.
      """)
  @Order(2)
  void aReusedIdIsRefusedAndOnlyAMachineWrites(Interactions story, Network net) {
    // One row is written here and several lookups decide the refusals, so the store is on this
    // diagram too — dashed, and declared where the story incurs it rather than once for the class.
    net.declare(NetworkEdge.JDBC, SERVICE, STORE, "insert one row, then compare against it by id");

    String id = UUID.randomUUID().toString();
    NEVER_IN_THE_BUNDLE.add(id);
    String envelope = StoryTarget.envelope(CONTESTED, OCCURRED_AT, PAYLOAD);

    // Five callers reach this service in this story and four of them reach the same route. Naming
    // each before it acts is the only thing that keeps them apart on the diagram — on the wire the
    // difference is two headers, which is exactly what a diagram must not print.
    NetworkCapture.actor(StoryTarget.OUTBOX);

    // The publisher's first, honest attempt — the row this story then contests.
    StoryTarget.publisher()
        .body(envelope)
        .when()
        .put(StoryTarget.event(id))
        .then()
        .statusCode(201);

    // (a) same id, different bytes. A reused UUID: 400, and the message says so rather than leaving
    // an outbox to guess.
    StoryTarget.publisher()
        .body(StoryTarget.envelope(CONTESTED, OCCURRED_AT, OTHER_PAYLOAD))
        .when()
        .put(StoryTarget.event(id))
        .then()
        .statusCode(400)
        .body("message", containsString("may not be reused"));
    // This 400 and the three below it are one edge — same caller, same route, same status. Which
    // field was contested is the story, and the story is in the notes.
    story
        .note(
            "a UUID reused for different content is 400 and not a conflict to poll on: no retry"
                + " fixes a publisher that reused an id, so telling it to try again would be a wait"
                + " with no end")
        .as("contested-payload-refused");

    // (b) the cause is inside the comparison too, and it is the sharper case: a publisher that
    // learned about causation between two attempts is claiming a different shape of history for one
    // id, and a server that kept the first quietly would leave two services disagreeing about it
    // with no error anywhere.
    String otherCause = UUID.randomUUID().toString();
    NEVER_IN_THE_BUNDLE.add(otherCause);
    StoryTarget.publisher()
        .body(StoryTarget.envelope(CONTESTED, OCCURRED_AT, PAYLOAD, otherCause, "platform"))
        .when()
        .put(StoryTarget.event(id))
        .then()
        .statusCode(400);
    story
        .note(
            "the CAUSE is inside the comparison too, and it is the sharper case: two PUTs of one id"
                + " claiming different parents are two claims about history, and a server that kept"
                + " the first quietly would leave two services disagreeing with no error anywhere")
        .as("contested-parent-refused");

    // (c) and the TIER, by the same argument: one id claiming two environments is two claims about
    // history. Same bytes, same cause, same instant — only `environment` differs, so this is the
    // narrowest possible statement that the field is inside the comparison rather than beside it.
    StoryTarget.publisher()
        .body(StoryTarget.envelope(CONTESTED, OCCURRED_AT, PAYLOAD, null, "dev"))
        .when()
        .put(StoryTarget.event(id))
        .then()
        .statusCode(400);
    story
        .note(
            "and the TIER by the same argument: one id claiming two environments is two claims"
                + " about history, so `environment` is inside the comparison rather than beside it"
                + " — everything else about this attempt is byte-identical to the first")
        .as("contested-environment-refused");

    // (d) an event cannot cause itself. Decidable from a single row with no graph to consult, which
    // is what makes it validation rather than analysis — and note what is deliberately NOT here: a
    // parent this log has never seen is stored as it stands, because nothing orders a parent's
    // arrival before its child's and a 400 is unretryable.
    String selfCaused = UUID.randomUUID().toString();
    NEVER_IN_THE_BUNDLE.add(selfCaused);
    StoryTarget.publisher()
        .body(StoryTarget.envelope(CONTESTED, OCCURRED_AT, PAYLOAD, selfCaused, "platform"))
        .when()
        .put(StoryTarget.event(selfCaused))
        .then()
        .statusCode(400)
        .body("message", containsString("own parent"));
    story
        .note(
            "an event may not be its own cause — decidable from one row, so validation rather than"
                + " analysis. What is deliberately NOT refused beside it: a parent this log has"
                + " never seen, because nothing orders a parent's arrival before its child's and a"
                + " 400 is unretryable. A dangling parent is data")
        .as("self-caused-event-refused");

    // (e) an environment that could never have been stored. The guard is SHAPE — a dns-safe name —
    // and deliberately not a lookup against qits-deployments' environments, which can delete an
    // environment after its events truthfully happened, turning history into a 400 nobody can fix.
    StoryTarget.publisher()
        .body(StoryTarget.envelope(CONTESTED, OCCURRED_AT, PAYLOAD, null, "Not A Tier"))
        .when()
        .put(StoryTarget.event(UUID.randomUUID().toString()))
        .then()
        .statusCode(400)
        .body("message", containsString("environment"));
    story
        .note(
            "an environment that is not a dns-safe name is refused on SHAPE — and deliberately not"
                + " by looking it up in qits-deployments, which can delete an environment after its"
                + " events truthfully happened, turning history into a 400 nobody can retry")
        .as("unstorable-environment-refused");

    // (f) an id that is not a UUID. Refused before any lookup: on PUT the id IS the idempotency
    // key. The literal survives the label scrubber — a bare number would have been rewritten to
    // {id} and the diagram would show a well-formed id being refused.
    StoryTarget.publisher()
        .body(envelope)
        .when()
        .put(EVENTS + "/not-a-uuid")
        .then()
        .statusCode(400);
    story
        .note(
            "an id that is not a UUID never gets that far: on this route the id IS the idempotency"
                + " key, and a caller that cannot spell one has no retry-safe identity to offer")
        .as("unspellable-id-refused");

    // (g) the read side's refusals, and they belong to the consumer of the story above. `order` is
    // a parameter this service defined, so a misspelling is a client error; falling back to
    // descending would answer a catch-up read with the HEAD of the log.
    NetworkCapture.actor(StoryTarget.DURABLE_CONSUMER);
    StoryTarget.consumer()
        .queryParam("name", CONTESTED)
        .queryParam("order", "sideways")
        .when()
        .get(EVENTS)
        .then()
        .statusCode(400)
        .body("message", containsString("order"));
    // The misspelling is in the query string and never reaches the label; what the diagram shows is
    // a consumer's read answered 400, which is the fact that matters.
    story
        .note(
            "a misspelled `order` is refused for the reason the parameter exists: answering it with"
                + " the opposite direction would hand a catch-up consumer the HEAD of the log and"
                + " let it record a watermark it never reached")
        .as("misspelled-order-refused");

    // …and the watermark itself has a shape. A cursor is composite — <occurredAt>,<id> — because
    // occurredAt ties by construction (a pipeline run's events carry the run's finish instant), and
    // a scalar cursor would split a fork across a page boundary. A cursor that is not the pair is
    // therefore not a looser question, it is one this route cannot answer.
    StoryTarget.consumer()
        .queryParam("name", CONTESTED)
        .queryParam("cursor", "the-last-row-i-saw")
        .when()
        .get(EVENTS)
        .then()
        .statusCode(400)
        .body("message", containsString("cursor"));
    story
        .note(
            "and a cursor that is not <occurredAt>,<id> is refused too: the pair is composite"
                + " because occurredAt TIES by construction, and a consumer resuming from half of"
                + " it would silently skip a row inside the tie")
        .as("half-a-cursor-refused");

    // --- the doors. Unreachable from any @QuarkusTest here: the %test dev-user hands every request
    // all four platform roles, and ForwardAuthMechanism is LaunchMode-guarded away in this process.

    // No header at all: ForwardAuthMechanism yields no identity, so @RolesAllowed challenges.
    NetworkCapture.actor(StoryTarget.UNNAMED);
    given()
        .contentType(ContentType.JSON)
        .body(envelope)
        .when()
        .put(StoryTarget.event(UUID.randomUUID().toString()))
        .then()
        .statusCode(401);
    story
        .note(
            "a caller the edge never named is challenged at the write — a status unreachable from"
                + " any @QuarkusTest here, where the %test dev-user hands every request all four"
                + " platform roles")
        .as("unnamed-publisher-challenged");

    // The same door on the stream, and it is the HANDSHAKE that holds it — class-level
    // @RolesAllowed on a websockets-next endpoint is checked on the HTTP upgrade, so an
    // unauthorised consumer is refused the connection rather than connected and then ignored. A
    // socket that upgraded and stayed silent would be indistinguishable from a bus with nothing to
    // say.
    //
    // The refusal is an EDGE and not a note, because the client sees it: FakeSubscriber observes
    // the failed dial from inside its own catch block, before dial() returns. What it cannot see is
    // WHY — a rejected upgrade and an unreachable port look the same from there — so the label says
    // `-> refused` and this assertion is what says which.
    NetworkCapture.actor(StoryTarget.UNNAMED_CONSUMER);
    assertThrows(
        Exception.class,
        () -> FakeSubscriber.dial(stream),
        "an unnamed subscriber must be refused the upgrade, not connected and ignored");
    story
        .note(
            "the same door on the stream, and it is the HANDSHAKE that holds it: a class-level"
                + " @RolesAllowed on a websockets-next endpoint is checked on the HTTP upgrade, so"
                + " an unauthorised consumer is refused the connection rather than connected and"
                + " ignored — which would be indistinguishable from a bus with nothing to say")
        .as("unnamed-consumer-refused");

    // Named by the edge and holding the PERSON's role: authenticated, and still not a publisher.
    // 403 and not 401, because there is a caller and it is the grant that is missing — collapsing
    // the two would tell an operator to log in again for something logging in cannot fix.
    NetworkCapture.actor(StoryTarget.PERSON_SESSION);
    StoryTarget.operatorWriting()
        .body(envelope)
        .when()
        .put(StoryTarget.event(UUID.randomUUID().toString()))
        .then()
        .statusCode(403);
    story
        .note(
            "a person's session — named, trusted, holding the role that reads every page of the"
                + " events client — still cannot put anything into the log")
        .as("person-may-not-publish");

    // …and the assertion that makes the three above mean something: the same person's session
    // READS the log perfectly well. A service that had simply been locked would pass all of them.
    StoryTarget.operator()
        .queryParam("name", CONTESTED)
        .when()
        .get(EVENTS)
        .then()
        .statusCode(200)
        .body("events.id", hasItem(id));
    story
        .note(
            "…and the same session READS the log perfectly well, which is what makes the refusals"
                + " mean something: a service that had simply been locked would pass all of them."
                + " The log is written by services and read by people")
        .as("person-still-reads");
  }

  @AfterAll
  static void bothStoryReportsAreComplete() {
    // The extension emits each report in its afterEach, so both are on disk before @AfterAll runs.
    // assertComplete also proves the network section: the sidecar's edges are canonical, the
    // networkHash recomputes from them, and every mermaid line is in the markdown.
    ReportAssertions.assertComplete(CATEGORY, DELIVERY_SLUG, UserflowReport.PASSED);

    // --- the round trip's whole graph, all four kinds -------------------------------------------
    // The dial, observed inside FakeSubscriber because no HTTP filter can see a websocket.
    ReportAssertions.assertEdge(
        CATEGORY,
        DELIVERY_SLUG,
        NetworkEdge.SOCKET,
        StoryTarget.DURABLE_CONSUMER,
        SERVICE,
        "WS " + STREAM + " subscribe");
    // The push, pointing the OTHER way on the same connection, because a push is the server's
    // decision. However many frames arrived, they are one arrow — the count is the story's job.
    ReportAssertions.assertEdge(
        CATEGORY,
        DELIVERY_SLUG,
        NetworkEdge.EVENT,
        SERVICE,
        StoryTarget.DURABLE_CONSUMER,
        "EventCreated frame");
    // The writes: a create and a replay, two statuses and therefore two edges. That pair is the
    // whole of idempotent publishing as a diagram can show it.
    ReportAssertions.assertEdge(
        CATEGORY, DELIVERY_SLUG, NetworkEdge.HTTP, StoryTarget.OUTBOX, SERVICE,
        StoryTarget.published(201));
    ReportAssertions.assertEdge(
        CATEGORY, DELIVERY_SLUG, NetworkEdge.HTTP, StoryTarget.OUTBOX, SERVICE,
        StoryTarget.published(200));
    // The catch-up: three reads, one edge — they differ only in a query string, which never reaches
    // a label.
    ReportAssertions.assertEdge(
        CATEGORY, DELIVERY_SLUG, NetworkEdge.HTTP, StoryTarget.DURABLE_CONSUMER, SERVICE,
        StoryTarget.read(EVENTS, 200));
    // And the store, which no tap can see.
    ReportAssertions.assertDeclaredEdge(
        CATEGORY,
        DELIVERY_SLUG,
        NetworkEdge.JDBC,
        SERVICE,
        STORE,
        "insert one row, then page it ascending from a watermark");
    // EXACTLY those six. A seventh would mean a probe the tap's skip missed, or a frame pushed to a
    // caller this story never named — and the whole delivery claim is about what did NOT also
    // happen.
    ReportAssertions.assertEdgeCount(CATEGORY, DELIVERY_SLUG, 6);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY,
        DELIVERY_SLUG,
        List.of(StoryTarget.DURABLE_CONSUMER, StoryTarget.OUTBOX, SERVICE));

    for (String step :
        List.of(
            "subscribed",
            "published",
            "delivered",
            "replayed-silently",
            "watermark-taken",
            "caught-up",
            "log-holds-it-once")) {
      ReportAssertions.assertStepId(CATEGORY, DELIVERY_SLUG, step);
    }

    ReportAssertions.assertComplete(CATEGORY, REFUSAL_SLUG, UserflowReport.PASSED);

    // --- the refusal story's whole graph --------------------------------------------------------
    // Four callers on one route, five statuses. Nothing on the wire tells them apart, so every one
    // of these edges exists because the story named its initiator before it acted.
    ReportAssertions.assertEdge(
        CATEGORY, REFUSAL_SLUG, NetworkEdge.HTTP, StoryTarget.OUTBOX, SERVICE,
        StoryTarget.published(201));
    ReportAssertions.assertEdge(
        CATEGORY, REFUSAL_SLUG, NetworkEdge.HTTP, StoryTarget.OUTBOX, SERVICE,
        StoryTarget.published(400));
    // Not a UUID, so the scrubber leaves it alone — which is the point: the id it could not spell
    // is on the diagram.
    ReportAssertions.assertEdge(
        CATEGORY,
        REFUSAL_SLUG,
        NetworkEdge.HTTP,
        StoryTarget.OUTBOX,
        SERVICE,
        StoryTarget.served("PUT", EVENTS + "/not-a-uuid", 400));
    ReportAssertions.assertEdge(
        CATEGORY, REFUSAL_SLUG, NetworkEdge.HTTP, StoryTarget.DURABLE_CONSUMER, SERVICE,
        StoryTarget.read(EVENTS, 400));
    ReportAssertions.assertEdge(
        CATEGORY, REFUSAL_SLUG, NetworkEdge.HTTP, StoryTarget.UNNAMED, SERVICE,
        StoryTarget.published(401));
    // The socket's door, observed from the client's own catch block.
    ReportAssertions.assertEdge(
        CATEGORY,
        REFUSAL_SLUG,
        NetworkEdge.SOCKET,
        StoryTarget.UNNAMED_CONSUMER,
        SERVICE,
        "WS " + STREAM + " subscribe -> refused");
    ReportAssertions.assertEdge(
        CATEGORY, REFUSAL_SLUG, NetworkEdge.HTTP, StoryTarget.PERSON_SESSION, SERVICE,
        StoryTarget.published(403));
    ReportAssertions.assertEdge(
        CATEGORY, REFUSAL_SLUG, NetworkEdge.HTTP, StoryTarget.PERSON_SESSION, SERVICE,
        StoryTarget.read(EVENTS, 200));
    ReportAssertions.assertDeclaredEdge(
        CATEGORY,
        REFUSAL_SLUG,
        NetworkEdge.JDBC,
        SERVICE,
        STORE,
        "insert one row, then compare against it by id");
    // And no tenth — in particular no `event` edge at all: nothing in this story ever subscribed
    // successfully, so nothing may have been pushed to anybody. That is the diagram's own form of
    // the claim, and it is what an assertEdge list alone cannot say.
    ReportAssertions.assertEdgeCount(CATEGORY, REFUSAL_SLUG, 9);
    ReportAssertions.assertNoEdgesTo(CATEGORY, REFUSAL_SLUG, StoryTarget.UNNAMED_CONSUMER);

    for (String step :
        List.of(
            "contested-payload-refused",
            "contested-parent-refused",
            "contested-environment-refused",
            "self-caused-event-refused",
            "unstorable-environment-refused",
            "unspellable-id-refused",
            "misspelled-order-refused",
            "half-a-cursor-refused",
            "unnamed-publisher-challenged",
            "unnamed-consumer-refused",
            "person-may-not-publish",
            "person-still-reads")) {
      ReportAssertions.assertStepId(CATEGORY, REFUSAL_SLUG, step);
    }

    // Nothing on this wire is a credential — the edge authenticates and this service reads two
    // headers — so what these assertions protect is the HASHES: every id these stories minted is
    // run-local, and one reaching a note or a label is exactly the symptom of a definitionHash or
    // networkHash that will never settle.
    for (String id : NEVER_IN_THE_BUNDLE) {
      ReportAssertions.assertNotLeaked(CATEGORY, DELIVERY_SLUG, id);
      ReportAssertions.assertNotLeaked(CATEGORY, REFUSAL_SLUG, id);
    }
  }
}
