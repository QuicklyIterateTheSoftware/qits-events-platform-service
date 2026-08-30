package eu.wohlben.qits.events.stories.silence;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import eu.wohlben.qits.userflows.report.Slugs;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.restassured.http.ContentType;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
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
 * <b>"Only a create broadcasts" is the clause every subscriber on this platform is written against,
 * and it is an absence — which is the one thing a presence check cannot state and a passively
 * captured diagram cannot draw.</b> This class turns it into evidence.
 *
 * <h2>Two stories, ONE connection, and that is the whole design</h2>
 *
 * <p>A negative claim about a push is only worth something if a tap could have seen the push. So the
 * first story opens a connection subscribed to {@code ["*"]} — <b>everything</b>, so that nothing
 * selective can explain a later silence — and proves it live the only way this protocol allows, by
 * being pushed frames for both of the service's write paths. The second story then does every other
 * thing this service can be asked to do <em>on that same connection</em>, and claims, at the level of
 * the diagram, that <b>no arrow reached it</b>.
 *
 * <p><b>The connection is the evidence. Do not close it between the two stories</b>, and do not
 * reorder them: {@code @TestMethodOrder} here is load-bearing rather than cosmetic, which is the one
 * place in this catalogue that is true. A reconnect between them would restore the flake this design
 * exists to remove — a fresh subscription cannot be proven live without a frame, and a frame is
 * exactly the arrow the second story says is absent.
 *
 * <h2>Why the claim is {@code assertNoEdgesTo} and never {@code assertNoEdgesFrom(SERVICE)}</h2>
 *
 * <p>Because the second one would be false. qits-events answers nothing without its store: a replay
 * is a lookup, a refusal on content is a comparison against a row, a delete is a delete. Every story
 * in this catalogue declares that {@code jdbc} edge where it incurs it, a declared edge counts in
 * {@code assertNoEdgesFrom}, and it should — the claim is "nothing left this process", and something
 * did. What is true, and is the claim actually worth making, is that nothing left this process
 * <em>towards the subscriber</em>.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class QuietBusIT {

  static final String CATEGORY = "silence";

  static final String CATEGORY_SLUG = Slugs.slug(CATEGORY);

  static final String ANNOUNCED = "Both write paths announce, and a subscriber of everything hears both";

  static final String ANNOUNCED_SLUG = Slugs.slug(ANNOUNCED);

  static final String QUIET =
      "Nothing this bus refuses, replays, reads or removes is pushed to anybody";

  static final String QUIET_SLUG = Slugs.slug(QUIET);

  /** This class's own vocabulary. */
  private static final String PUBLISHED = "UserflowQuietPublished";

  private static final String RECORDED = "UserflowQuietRecorded";

  private static final String AT = "2026-08-24T06:00:00Z";

  private static final String PAYLOAD = "{\"repository\":\"qits-events\"}";

  private static final String OTHER_PAYLOAD = "{\"repository\":\"somebody-elses\"}";

  /** Subscribed to EVERYTHING, so that no selectivity can explain the second story's silence. */
  private static final String LISTENER = "a subscriber of everything";

  /**
   * The connection both stories share. Opened by the first, kept open across the story border, and
   * closed in {@code @AfterAll} — see the class javadoc for why that is the design and not a leak.
   */
  private static FakeSubscriber listener;

  /** The row the second story replays, contests and reads. Published by the first. */
  private static String publishedRow;

  /** The row the second story deletes. Recorded by hand by the first, through {@code POST}. */
  private static String recordedRow;

  private static final List<String> NEVER_IN_THE_BUNDLE = new ArrayList<>();

  @TestHTTPResource(StoryTarget.STREAM)
  URI stream;

  @BeforeAll
  static void tapTheBus() {
    StoryNetwork.install();
  }

  @UserStory(value = ANNOUNCED, category = CATEGORY)
  @UserStoryDescription(
      """
      A consumer opens the stream and subscribes to `["*"]` — everything there is. That is a
      deliberate choice and not laziness: what comes next is a story about silence, and a
      connection that had named signatures could always be silent because it named the wrong
      ones.

      Two rows are then written, by the two paths this service has, and the consumer is pushed
      both. `PUT /{id}` is the bus's publish, under the publisher's own UUID. `POST` is the
      manual record path — a person or a script with nothing to retry — and it announces too,
      because the CDI signal is fired from EventService rather than from either boundary, so the
      two write paths cannot drift apart about what an event IS or about who gets told.

      That is the whole of this story, and its purpose is to leave a connection open that is
      demonstrably receiving. The story after it needs exactly that, and cannot build it for
      itself.
      """)
  @Order(1)
  void bothWritePathsAnnounceToASubscriberOfEverything(Interactions story, Network net)
      throws Exception {
    net.declare(
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "insert one row per write, on either path");

    NetworkCapture.actor(LISTENER);
    listener = FakeSubscriber.dial(stream, StoryTarget.machineCredential());
    listener.subscribe("*");

    NetworkCapture.actor(StoryTarget.OUTBOX);
    StoryStream.awaitSubscriptionIsLive(listener, PUBLISHED, AT, PAYLOAD);
    StoryStream.drain(listener);
    story
        .note(
            "a consumer subscribes to [\"*\"] — everything — and proves it live the only way this"
                + " protocol allows. Everything rather than a signature on purpose: the story after"
                + " this one is about silence, and a connection that had named signatures could"
                + " always be silent because it named the wrong ones")
        .as("a-subscriber-of-everything-proven-live");

    // --- the bus's write path ------------------------------------------------------------------
    publishedRow = UUID.randomUUID().toString();
    NEVER_IN_THE_BUNDLE.add(publishedRow);
    StoryTarget.publisher()
        .body(StoryTarget.envelope(PUBLISHED, AT, PAYLOAD))
        .when()
        .put(StoryTarget.event(publishedRow))
        .then()
        .statusCode(201);
    assertNotNull(
        StoryStream.awaitFrame(listener, PUBLISHED),
        "a create on the publish path announces itself");
    story
        .note(
            "the bus's own write — PUT under the publisher's UUID — creates a row and announces it,"
                + " and the subscriber is pushed the envelope while the write is still warm")
        .as("the-publish-path-announces");

    // --- and the manual one, which is a different boundary and the SAME signal -------------------
    NetworkCapture.actor(StoryTarget.PERSON_SESSION);
    recordedRow =
        StoryTarget.operatorWriting()
            .body(StoryTarget.record(RECORDED, PAYLOAD))
            .when()
            .post(StoryTarget.EVENTS)
            .then()
            // 200, not 201: the manual path returns the created row rather than a Location, and it
            // has answered that way since before the bus existed. Measured rather than assumed —
            // the PUT beside it really does answer 201, so the two writes are genuinely different
            // shapes and not one route wearing two verbs.
            .statusCode(200)
            .extract()
            .path("event.id");
    NEVER_IN_THE_BUNDLE.add(recordedRow);
    assertNotNull(
        StoryStream.awaitFrame(listener, RECORDED),
        "a create on the manual path announces itself too — the signal is fired from EventService,"
            + " so the two boundaries cannot diverge about it");
    StoryStream.drain(listener);
    story
        .note(
            "and the MANUAL record path announces identically. The CDI signal is fired from"
                + " EventService rather than from either boundary, precisely so the two write paths"
                + " cannot drift apart about what an event is or about who gets told — a person"
                + " recording by hand and a service publishing reach the same subscribers. The two"
                + " routes are not one wearing two verbs, though, and the diagram shows it: POST"
                + " answers 200 with the created row, PUT answers 201 with it")
        .as("the-manual-path-announces-too");

    story
        .note(
            "this connection stays open into the next story, and it is that story's evidence:"
                + " 'nothing was pushed' is only worth something from a client that was demonstrably"
                + " being pushed to a moment earlier")
        .as("the-connection-is-the-next-storys-evidence");
  }

  @UserStory(value = QUIET, category = CATEGORY)
  @UserStoryDescription(
      """
      The same connection, still open, still subscribed to everything, and demonstrably receiving
      a moment ago. Now the bus is asked for everything else it can do.

      A publisher retries an attempt whose answer was lost: same id, same bytes, 200 rather than
      201. Nothing is written, and NOTHING IS PUSHED — a subscriber must not see an event twice
      because an acknowledgement went missing, and that clause is the reason a publisher's retry
      is safe at all.

      A publisher reuses a UUID for different content and is refused: 400, no row, no frame.

      A caller the edge never named is challenged, and a person's session — named, trusted,
      holding the role that reads every page of the events client — is forbidden. Neither
      reaches a row, so neither could have announced one, and the diagram says so.

      A person reads the log. A read is a read: it produces no row and therefore no frame, which
      is worth stating because a bus that echoed its own reads back onto the stream would loop
      the whole platform.

      And a person DELETES a row. This is the sharp one: only a CREATE broadcasts, so a removal
      is announced to nobody. A consumer that already handled that row is never told it went
      away and its watermark is not rewound by the deletion — the log is append-only to every
      reader of the stream, whatever an operator does to it afterwards.

      Six things happened, one row disappeared, and not a single frame was pushed to a connection
      that had asked for all of them.
      """)
  @Order(2)
  void nothingElseTheBusDoesIsPushedToAnybody(Interactions story, Network net) throws Exception {
    net.declare(
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "select event by id, then delete one row");

    assertTrue(
        listener != null && listener.isOpen(),
        "the connection the previous story proved live must still be open — it is this story's"
            + " evidence, not its fixture");

    // --- a retry whose answer was lost -----------------------------------------------------------
    NetworkCapture.actor(StoryTarget.OUTBOX);
    StoryTarget.publisher()
        .body(StoryTarget.envelope(PUBLISHED, AT, PAYLOAD))
        .when()
        .put(StoryTarget.event(publishedRow))
        .then()
        .statusCode(200);
    story
        .note(
            "a publisher retries an attempt whose answer was lost: the same id and the same bytes"
                + " come back 200 rather than 201, nothing is written, and nothing may be announced"
                + " — a subscriber must not see an event twice because an acknowledgement went"
                + " missing")
        .as("a-replay-writes-nothing-and-announces-nothing");

    // --- a reused UUID ---------------------------------------------------------------------------
    StoryTarget.publisher()
        .body(StoryTarget.envelope(PUBLISHED, AT, OTHER_PAYLOAD))
        .when()
        .put(StoryTarget.event(publishedRow))
        .then()
        .statusCode(400)
        .body("message", containsString("may not be reused"));
    story
        .note(
            "a publisher that reused a UUID for different content is refused — and a refusal is a"
                + " row that was never written, so there is nothing to announce either")
        .as("a-refusal-is-a-row-that-never-existed");

    // --- the two doors, neither of which reaches a row -------------------------------------------
    NetworkCapture.actor(StoryTarget.UNNAMED);
    given()
        .contentType(ContentType.JSON)
        .body(StoryTarget.envelope(PUBLISHED, AT, PAYLOAD))
        .when()
        .put(StoryTarget.event(mint()))
        .then()
        .statusCode(401);

    NetworkCapture.actor(StoryTarget.PERSON_SESSION);
    StoryTarget.operatorWriting()
        .body(StoryTarget.envelope(PUBLISHED, AT, PAYLOAD))
        .when()
        .put(StoryTarget.event(mint()))
        .then()
        .statusCode(403);
    story
        .note(
            "a caller the edge never named is challenged and a person's session is forbidden."
                + " Neither request reached a row, so neither could have announced one — the doors"
                + " are in front of the write, not beside it")
        .as("a-challenged-caller-announces-nothing");

    // --- a read is a read ------------------------------------------------------------------------
    StoryTarget.operator()
        .queryParam("name", PUBLISHED)
        .when()
        .get(StoryTarget.EVENTS)
        .then()
        .statusCode(200);
    story
        .note(
            "a person reads the log, which produces no row and therefore no frame. Worth stating"
                + " rather than assuming: a bus that echoed its own reads onto the stream would"
                + " loop the whole platform through one subscriber's poll")
        .as("a-read-produces-no-frame");

    // --- and the one destructive operation this service has --------------------------------------
    StoryTarget.operator()
        .when()
        .delete(StoryTarget.event(recordedRow))
        .then()
        .statusCode(200)
        .body("success", equalTo(true));
    story
        .note(
            "and a row is DELETED, which is announced to nobody: only a CREATE broadcasts. A"
                + " consumer that already handled that row is never told it went away and its"
                + " watermark is not rewound by the deletion — to every reader of the stream this"
                + " log is append-only, whatever an operator does to it afterwards")
        .as("a-deletion-is-announced-to-nobody");

    // --- the silence, once, on a connection that is still open -----------------------------------
    StoryStream.assertSilent(
        listener,
        "six requests, one row removed, and a connection subscribed to EVERYTHING must have been"
            + " pushed nothing at all");
    assertTrue(listener.isOpen(), "…and it is still open, so the silence is not a dead socket");
    story
        .note(
            "six things happened, one row disappeared, and not one frame was pushed to a connection"
                + " that had asked for all of them — and it is still open, so this is silence rather"
                + " than a socket that died quietly. The diagram states it as the absence of any"
                + " arrow reaching that consumer")
        .as("not-one-frame-was-pushed");
  }

  /** A UUID this story mints and never prints. */
  private static String mint() {
    String id = UUID.randomUUID().toString();
    NEVER_IN_THE_BUNDLE.add(id);
    return id;
  }

  @AfterAll
  static void bothQuietStoriesAreComplete() {
    if (listener != null) {
      listener.close();
    }

    ReportAssertions.assertComplete(CATEGORY_SLUG, ANNOUNCED_SLUG, UserflowReport.PASSED);

    // --- the announcing story's graph: five arrows -----------------------------------------------
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        ANNOUNCED_SLUG,
        NetworkEdge.SOCKET,
        LISTENER,
        StoryTarget.SERVICE,
        "WS " + StoryTarget.STREAM + " subscribe");
    // Every frame of both write paths, deduped to one: the label is a constant, which is what makes
    // an unpredictable frame count assertable.
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        ANNOUNCED_SLUG,
        NetworkEdge.EVENT,
        StoryTarget.SERVICE,
        LISTENER,
        "EventCreated frame");
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        ANNOUNCED_SLUG,
        NetworkEdge.HTTP,
        StoryTarget.OUTBOX,
        StoryTarget.SERVICE,
        StoryTarget.published(201));
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        ANNOUNCED_SLUG,
        NetworkEdge.HTTP,
        StoryTarget.PERSON_SESSION,
        StoryTarget.SERVICE,
        StoryTarget.posted(StoryTarget.EVENTS, 200));
    ReportAssertions.assertDeclaredEdge(
        CATEGORY_SLUG,
        ANNOUNCED_SLUG,
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "insert one row per write, on either path");
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, ANNOUNCED_SLUG, 5);

    for (String step :
        List.of(
            "a-subscriber-of-everything-proven-live",
            "the-publish-path-announces",
            "the-manual-path-announces-too",
            "the-connection-is-the-next-storys-evidence")) {
      ReportAssertions.assertStepId(CATEGORY_SLUG, ANNOUNCED_SLUG, step);
    }

    ReportAssertions.assertComplete(CATEGORY_SLUG, QUIET_SLUG, UserflowReport.PASSED);

    // --- the quiet story's graph: six requests and the store, and NOTHING pointing at the
    // subscriber ---------------------------------------------------------------------------------
    ReportAssertions.assertEdge(
        CATEGORY_SLUG, QUIET_SLUG, NetworkEdge.HTTP, StoryTarget.OUTBOX, StoryTarget.SERVICE,
        StoryTarget.published(200));
    ReportAssertions.assertEdge(
        CATEGORY_SLUG, QUIET_SLUG, NetworkEdge.HTTP, StoryTarget.OUTBOX, StoryTarget.SERVICE,
        StoryTarget.published(400));
    ReportAssertions.assertEdge(
        CATEGORY_SLUG, QUIET_SLUG, NetworkEdge.HTTP, StoryTarget.UNNAMED, StoryTarget.SERVICE,
        StoryTarget.published(401));
    ReportAssertions.assertEdge(
        CATEGORY_SLUG, QUIET_SLUG, NetworkEdge.HTTP, StoryTarget.PERSON_SESSION,
        StoryTarget.SERVICE, StoryTarget.published(403));
    ReportAssertions.assertEdge(
        CATEGORY_SLUG, QUIET_SLUG, NetworkEdge.HTTP, StoryTarget.PERSON_SESSION,
        StoryTarget.SERVICE, StoryTarget.read(StoryTarget.EVENTS, 200));
    ReportAssertions.assertEdge(
        CATEGORY_SLUG, QUIET_SLUG, NetworkEdge.HTTP, StoryTarget.PERSON_SESSION,
        StoryTarget.SERVICE, StoryTarget.deleted(StoryTarget.ANY_EVENT, 200));
    ReportAssertions.assertDeclaredEdge(
        CATEGORY_SLUG,
        QUIET_SLUG,
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "select event by id, then delete one row");

    // THE CLAIM, and the reason this class is two stories rather than one: nothing reached the
    // subscriber — from a connection that the story above proved is pushed to, kept open across the
    // border, and still open when this was asserted.
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, QUIET_SLUG, LISTENER);
    // …and no `event` edge of any kind, to anybody. There is no second connection in this story, so
    // "to nobody" and "to this listener" are the same set; stating both is what would catch a frame
    // pushed to a caller no story named.
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, QUIET_SLUG, 7);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY_SLUG,
        QUIET_SLUG,
        List.of(
            StoryTarget.OUTBOX,
            StoryTarget.UNNAMED,
            StoryTarget.PERSON_SESSION,
            StoryTarget.SERVICE));

    for (String step :
        List.of(
            "a-replay-writes-nothing-and-announces-nothing",
            "a-refusal-is-a-row-that-never-existed",
            "a-challenged-caller-announces-nothing",
            "a-read-produces-no-frame",
            "a-deletion-is-announced-to-nobody",
            "not-one-frame-was-pushed")) {
      ReportAssertions.assertStepId(CATEGORY_SLUG, QUIET_SLUG, step);
    }

    for (String id : NEVER_IN_THE_BUNDLE) {
      ReportAssertions.assertNotLeaked(CATEGORY_SLUG, ANNOUNCED_SLUG, id);
      ReportAssertions.assertNotLeaked(CATEGORY_SLUG, QUIET_SLUG, id);
    }
  }
}
