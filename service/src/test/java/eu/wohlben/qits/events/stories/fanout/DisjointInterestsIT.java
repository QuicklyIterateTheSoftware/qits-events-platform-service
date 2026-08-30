package eu.wohlben.qits.events.stories.fanout;

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
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * <b>Routing on this bus is the event's signature and each connection's own subscription set, and
 * nothing else.</b> There are no topics here, no exchanges, no per-consumer queue — {@code
 * EventStreamSubscriptions} is one {@code Map<connectionId, Set<signature>>} in one process, and a
 * publish walks it. That is the whole routing table, so the only thing worth proving about it is the
 * thing a table cannot state: that an event reached the connections that asked for it and <b>no
 * others</b>.
 *
 * <h2>Why three connections and not two</h2>
 *
 * <p>Two of them are selective and each is proven live: the build watcher and the release watcher
 * both receive the warm-up handshake, then each receives its own signature and stays silent through
 * the other's. That is <em>selectivity</em>, and it is what makes each one's silence evidence rather
 * than an empty queue.
 *
 * <p>The third has named nothing at all. {@code EventStreamSubscriptions.opened} puts a connection
 * in the table with an <b>empty</b> set, so it is subscribed to nothing until it says otherwise —
 * silence is the honest default for a connection that has not said what it wants, and it is what
 * keeps a browser tab that merely opened the socket out of the fan-out. It dials with the
 * <em>person's</em> credential, because that is who that tab belongs to and because the socket's
 * door really does take {@code qits:admin} beside {@code qits:system}.
 *
 * <p><b>And it is the one this story makes a diagram-level claim about.</b> {@code
 * assertNoEdgesTo("a tab that only opened the socket")} says nothing was ever pushed to it — a claim
 * that would be worthless from a client nobody could see, and is not, because that client is a
 * {@code FakeSubscriber} with the tap inside it, connected for the whole story, while two sibling
 * connections were drawing {@code event} arrows the entire time. A tap that could have seen the edge
 * and did not is the only kind of absence worth putting in a report.
 *
 * <h2>What is out of reach</h2>
 *
 * <p><b>Fan-out across two instances of this service</b>, because there is no such thing:
 * subscriptions are in-memory and single-process by design (a subscription is worth exactly as long
 * as the connection holding it, so there is nothing to replicate), and a second instance would need
 * a real broker rather than a shared table. That is a different feature, and this story would be the
 * one to grow when it arrives.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
public class DisjointInterestsIT {

  static final String CATEGORY = "fan-out";

  static final String CATEGORY_SLUG = Slugs.slug(CATEGORY);

  static final String STORY = "An event reaches the consumers that asked for it, and nobody else";

  static final String SLUG = Slugs.slug(STORY);

  /** This story's own vocabulary. Every class in this catalogue owns its names — see StoryProfile. */
  private static final String HANDSHAKE = "UserflowFanoutHandshake";

  private static final String BUILD = "UserflowFanoutBuild";

  private static final String RELEASE = "UserflowFanoutRelease";

  private static final String HANDSHAKE_AT = "2026-08-26T08:00:00Z";

  private static final String BUILD_AT = "2026-08-26T08:10:00Z";

  private static final String RELEASE_AT = "2026-08-26T08:20:00Z";

  private static final String PAYLOAD = "{\"repository\":\"qits-events\"}";

  // --- the three connections, named before they dial -------------------------------------------

  private static final String BUILD_WATCHER = "a consumer watching builds";

  private static final String RELEASE_WATCHER = "a consumer watching releases";

  /** The browser tab: connected, authorised, and subscribed to nothing because it never asked. */
  private static final String SILENT_TAB = "a tab that only opened the socket";

  /** Run-local ids that must appear in no file of the bundle. */
  private static final List<String> NEVER_IN_THE_BUNDLE = new ArrayList<>();

  @TestHTTPResource(StoryTarget.STREAM)
  URI stream;

  @BeforeAll
  static void tapTheBus() {
    StoryNetwork.install();
  }

  @UserStory(value = STORY, category = CATEGORY)
  @UserStoryDescription(
      """
      Three connections are open on the one bus at the same time, and they want different things.

      One names the build signature, one names the release signature, and one names nothing at
      all — a browser tab that opened the socket and never sent a subscribe frame, which on this
      protocol means subscribed to nothing rather than subscribed to everything. All three are
      authorised: the socket's door takes the machine role and the person's alike, so the tab is
      a legitimate connection and not an intruder.

      Both watchers first prove themselves live the only way this protocol allows — by being
      pushed a handshake event they both named, because a subscribe frame is answered with
      silence and a publish that raced one would look exactly like a broken fan-out.

      Then a build event is published. The build watcher is pushed it; the release watcher is
      not, and its silence means SELECTIVITY rather than a dead socket, because a release event
      published straight afterwards reaches it and only it. One publish, one matching
      connection, one frame.

      And through all of it the tab is told nothing. That is the claim this story exists for, and
      it is a claim about traffic that did not happen — which only means something because the
      tab is a real client with the report's own tap inside it, connected the whole time, while
      two connections beside it were being pushed frames.
      """)
  void anEventReachesOnlyTheConsumersThatAskedForIt(Interactions story, Network net)
      throws Exception {
    net.declare(NetworkEdge.JDBC, StoryTarget.SERVICE, StoryTarget.STORE, "insert one row per publish");

    // Each consumer is NAMED before it dials: FakeSubscriber reads the actor once, at the dial, and
    // keeps it — a frame arrives on a Vert.x event-loop thread at a moment no story controls, and a
    // tap reading the sticky actor there would name whoever the story happened to be acting as.
    NetworkCapture.actor(BUILD_WATCHER);
    try (FakeSubscriber builds = FakeSubscriber.dial(stream, StoryTarget.machineCredential());
        FakeSubscriber releases = dialAs(RELEASE_WATCHER);
        FakeSubscriber tab = dialAsThePerson()) {

      builds.subscribe(HANDSHAKE, BUILD);
      releases.subscribe(HANDSHAKE, RELEASE);
      // …and the tab sends NOTHING. That is the whole of its participation, and it is why it hears
      // nothing: opened() seats a connection with an empty subscription set.
      assertTrue(tab.isOpen(), "the person's credential is a legitimate upgrade on this socket");

      NetworkCapture.actor(StoryTarget.OUTBOX);
      awaitBothSubscriptionsAreLive(builds, releases);
      story
          .note(
              "three connections are open at once: one names the build signature, one names the"
                  + " release signature, and one names nothing at all. The two that named something"
                  + " prove it the only way this protocol allows — a subscribe frame is answered"
                  + " with silence, so the proof is a frame arriving")
          .as("three-connections-two-subscriptions");

      StoryStream.drain(builds);
      StoryStream.drain(releases);
      StoryStream.assertSilent(
          tab,
          "a connection that has sent no subscribe frame is subscribed to NOTHING, and the handshake"
              + " fan-out that just reached two connections beside it must not have reached it");
      story
          .note(
              "the tab is already the odd one out: the handshake reached the two connections that"
                  + " named it and not the one that named nothing. Silence is the honest default"
                  + " for a connection that has not said what it wants — it is what keeps a browser"
                  + " tab that merely opened the socket out of the fan-out")
          .as("a-connection-that-named-nothing-gets-nothing");

      // --- one publish, one matching connection ------------------------------------------------
      publish(BUILD, BUILD_AT);
      assertNotNull(
          StoryStream.awaitFrame(builds, BUILD),
          "the build watcher named this signature and must be pushed it");
      StoryStream.assertSilent(
          releases,
          "the release watcher named a different signature: routing here is the event's name"
              + " against each connection's own set, so this frame is not its business");
      StoryStream.assertSilent(tab, "and the tab named nothing at all");
      story
          .note(
              "a build event is published and exactly one connection is pushed it. The release"
                  + " watcher's silence is the interesting half: routing on this bus is the event's"
                  + " signature against each connection's own subscription set, and there is no"
                  + " topic, no exchange and no per-consumer queue behind it")
          .as("the-interested-consumer-is-pushed-it");

      // --- and the mirror, which is what makes the silence above mean SELECTIVITY ---------------
      publish(RELEASE, RELEASE_AT);
      assertNotNull(
          StoryStream.awaitFrame(releases, RELEASE),
          "the release watcher was live all along — its earlier silence was selectivity");
      StoryStream.assertSilent(builds, "and now it is the build watcher's turn to be uninterested");
      StoryStream.assertSilent(tab, "the tab is told nothing, whatever is published");
      story
          .note(
              "then a release event, and the two swap places. That is what makes the silence above"
                  + " evidence rather than an empty queue: a socket that had died would have been"
                  + " silent for both, and this one was silent for exactly the event it had not"
                  + " asked for")
          .as("and-the-other-consumer-for-its-own-event");

      story
          .note(
              "through all of it the tab received nothing — which the diagram states as the absence"
                  + " of any arrow reaching it. That claim is worth something only because the tab"
                  + " is a real client with the report's own tap inside it, connected the whole"
                  + " time, while two connections beside it were being pushed frames")
          .as("nothing-was-ever-pushed-to-the-tab");
    }
  }

  /** Dial as {@code actor} with a sibling service's machine credential. */
  private FakeSubscriber dialAs(String actor) throws Exception {
    NetworkCapture.actor(actor);
    return FakeSubscriber.dial(stream, StoryTarget.machineCredential());
  }

  /** The tab: the person's two headers, which this socket's door accepts exactly as it does a machine's. */
  private FakeSubscriber dialAsThePerson() throws Exception {
    NetworkCapture.actor(SILENT_TAB);
    return FakeSubscriber.dial(stream, StoryTarget.personCredential());
  }

  /**
   * Publish handshake events until <b>both</b> watchers have been pushed one.
   *
   * <p>Each attempt is its own UUID and therefore its own CREATE, because only a create broadcasts.
   * One publish reaches both connections, so this normally makes exactly one — but it loops, because
   * "the subscription has been applied" is not a state this protocol lets a client ask about.
   */
  private static void awaitBothSubscriptionsAreLive(FakeSubscriber builds, FakeSubscriber releases)
      throws Exception {
    boolean buildsLive = false;
    boolean releasesLive = false;
    long deadline = System.nanoTime() + StoryStream.ARRIVAL.toNanos();
    while (System.nanoTime() < deadline) {
      publish(HANDSHAKE, HANDSHAKE_AT);
      buildsLive = buildsLive || builds.next(Duration.ofSeconds(2)) != null;
      releasesLive = releasesLive || releases.next(Duration.ofSeconds(2)) != null;
      if (buildsLive && releasesLive) {
        return;
      }
    }
    throw new AssertionError(
        "the packaged artifact never pushed the handshake to both watchers — builds live: "
            + buildsLive
            + ", releases live: "
            + releasesLive);
  }

  /** One create, under an id the publisher chose. The id is run-local and never reaches the report. */
  private static void publish(String signature, String occurredAt) {
    String id = UUID.randomUUID().toString();
    NEVER_IN_THE_BUNDLE.add(id);
    StoryTarget.publisher()
        .body(StoryTarget.envelope(signature, occurredAt, PAYLOAD))
        .when()
        .put(StoryTarget.event(id))
        .then()
        .statusCode(201);
  }

  @AfterAll
  static void theFanOutStoryIsComplete() {
    ReportAssertions.assertComplete(CATEGORY_SLUG, SLUG, UserflowReport.PASSED);

    // --- the graph ------------------------------------------------------------------------------
    // Three dials, one label: the socket's address is the same for all of them and the difference is
    // entirely the actor, which is the story's own statement and not something the wire carries.
    dialled(BUILD_WATCHER);
    dialled(RELEASE_WATCHER);
    dialled(SILENT_TAB);

    // Two pushes, pointing the other way. However many frames each connection received across the
    // handshake and its own event, they are ONE arrow each — dedupe on the whole quadruple is what
    // makes a nondeterministic frame count assertable at all.
    pushedTo(BUILD_WATCHER);
    pushedTo(RELEASE_WATCHER);

    // Every publish in this story: one caller, one route, one status.
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        SLUG,
        NetworkEdge.HTTP,
        StoryTarget.OUTBOX,
        StoryTarget.SERVICE,
        StoryTarget.published(201));

    ReportAssertions.assertDeclaredEdge(
        CATEGORY_SLUG,
        SLUG,
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "insert one row per publish");

    // THE CLAIM. Nothing reached the connection that asked for nothing — from a client that was
    // connected, tapped and sitting beside two others that were being pushed frames.
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, SLUG, SILENT_TAB);

    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, SLUG, 7);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY_SLUG,
        SLUG,
        List.of(BUILD_WATCHER, RELEASE_WATCHER, SILENT_TAB, StoryTarget.OUTBOX, StoryTarget.SERVICE));

    for (String step :
        List.of(
            "three-connections-two-subscriptions",
            "a-connection-that-named-nothing-gets-nothing",
            "the-interested-consumer-is-pushed-it",
            "and-the-other-consumer-for-its-own-event",
            "nothing-was-ever-pushed-to-the-tab")) {
      ReportAssertions.assertStepId(CATEGORY_SLUG, SLUG, step);
    }

    for (String id : NEVER_IN_THE_BUNDLE) {
      ReportAssertions.assertNotLeaked(CATEGORY_SLUG, SLUG, id);
    }
  }

  private static void dialled(String consumer) {
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        SLUG,
        NetworkEdge.SOCKET,
        consumer,
        StoryTarget.SERVICE,
        "WS " + StoryTarget.STREAM + " subscribe");
  }

  private static void pushedTo(String consumer) {
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        SLUG,
        NetworkEdge.EVENT,
        StoryTarget.SERVICE,
        consumer,
        "EventCreated frame");
  }
}
