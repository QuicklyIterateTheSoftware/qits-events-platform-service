package eu.wohlben.qits.events.stories.subscription;

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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * <b>The subscribe frame is the only thing a client of this bus ever sends, and every rule about it
 * is a rule about a long-lived connection.</b> A consumer's interest changes across a deploy, a
 * feature flag, a config reload; the connection does not. So the protocol says
 * <em>replace</em> rather than add, and it says a frame it cannot read costs the frame and not the
 * socket — and both of those are promises about what happens to a connection that has been open for
 * a week, which is exactly the situation a unit test never reaches.
 *
 * <h2>Every phase is proven, because this protocol acknowledges nothing</h2>
 *
 * <p>{@code {"subscribe": [...]}} goes out and the server answers with silence. There is no ack, no
 * echo and no "current subscription" read model — deliberately, because the set is worth exactly as
 * long as the connection holding it. So a story cannot assert "the frame was applied"; it can only
 * publish under the new signature until something arrives. <b>Every arm below therefore proves the
 * frame landed before it asserts what the frame changed</b>, and an arm that skipped that step would
 * be indistinguishable from one whose frame was still in flight.
 *
 * <h2>One arrow for however many frames</h2>
 *
 * <p>Five phases push an unpredictable number of frames down one connection — the warm-up loop alone
 * publishes until something arrives. They dedupe to a <b>single</b> {@code event} edge, because an
 * edge is the quadruple {@code (kind, from, to, label)} and the label is a constant. That is what
 * makes a nondeterministic frame count assertable at all: the diagram says a push happened, and the
 * story's own assertions say what was in it and how many times.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
public class SubscriptionFramesIT {

  static final String CATEGORY = "subscription";

  static final String CATEGORY_SLUG = Slugs.slug(CATEGORY);

  static final String STORY =
      "A consumer narrows what it wants, garbles a frame, and keeps its connection";

  static final String SLUG = Slugs.slug(STORY);

  /** This story's own vocabulary — three names no other class in this catalogue publishes. */
  private static final String ALPHA = "UserflowSubscribeAlpha";

  private static final String BETA = "UserflowSubscribeBeta";

  /** A signature the consumer never names — which is the whole point of the {@code "*"} arm. */
  private static final String GAMMA = "UserflowSubscribeGamma";

  private static final String AT = "2026-08-25T07:00:00Z";

  private static final String PAYLOAD = "{\"repository\":\"qits-events\"}";

  /** One connection for the whole story, which is what every rule here is about. */
  private static final String CONSUMER = "a long-lived consumer";

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
      One connection, open from the first line to the last, changing its mind four times.

      It starts by naming one signature and is pushed an event under it — which is the only way
      a client of this protocol ever learns its subscription took, because a subscribe frame is
      answered with nothing at all.

      Then it sends a second frame naming a different signature. That frame REPLACES the set
      rather than adding to it: a client that wants less has no other way to say so, and a set
      that only ever grew would make a long-lived connection's interest a function of its whole
      history. So the new signature arrives and the old one stops — and the story proves the
      replacement landed before it asserts the silence, because otherwise "not subscribed any
      more" and "the frame has not been applied yet" are the same observation.

      Then it sends rubbish: text that is not JSON, and JSON whose `subscribe` is not an array.
      Both are dropped with a debug line and the connection is left open — one malformed frame
      from a client must not cost it a connection — and the subscription it already had is
      untouched, which the next delivered event proves.

      Then `["*"]`, which means everything: an event under a signature this consumer has never
      named reaches it.

      And finally a frame carrying a blank string and a number beside a real name. The
      unusable entries are ignored rather than rejected — the array is a statement of interest,
      and there is no interest a blank string could express — so the connection ends up
      subscribed to exactly the one name it spelled properly, and `"*"` is gone, because
      replacement is replacement.
      """)
  void aConsumerNarrowsWhatItWantsAndKeepsItsConnection(Interactions story, Network net)
      throws Exception {
    net.declare(
        NetworkEdge.JDBC, StoryTarget.SERVICE, StoryTarget.STORE, "insert one row per publish");

    NetworkCapture.actor(CONSUMER);
    try (FakeSubscriber consumer = FakeSubscriber.dial(stream, StoryTarget.machineCredential())) {

      // --- phase 1: one signature, proven live ---------------------------------------------------
      consumer.subscribe(ALPHA);
      NetworkCapture.actor(StoryTarget.OUTBOX);
      awaitLive(consumer, ALPHA);
      StoryStream.drain(consumer);
      story
          .note(
              "the connection names one signature and is pushed an event under it. That frame is"
                  + " the only acknowledgement this protocol has: {\"subscribe\": [...]} is"
                  + " answered with silence, so a client learns its subscription took by receiving"
                  + " something or not at all")
          .as("one-signature-proven-by-a-frame");

      // --- phase 2: replace, not add -------------------------------------------------------------
      consumer.subscribe(BETA);
      // Prove the REPLACEMENT landed before asserting what it removed. Publishing BETA until a
      // frame arrives is the proof; it is also why the silence below cannot be "not applied yet".
      awaitLive(consumer, BETA);
      StoryStream.drain(consumer);
      publish(ALPHA);
      StoryStream.assertSilent(
          consumer,
          "a subscribe frame REPLACES the connection's set: having named beta, this connection is"
              + " no longer subscribed to alpha");
      story
          .note(
              "a second frame naming a different signature REPLACES the set rather than adding to"
                  + " it — a client that wants less has no other way to say so, and a set that only"
                  + " ever grew would make a long-lived connection's interest a function of its"
                  + " whole history. The new name arrives; the old one has stopped")
          .as("subscribe-replaces-it-does-not-add");

      // --- phase 3: rubbish costs the frame and not the connection -------------------------------
      consumer.sendRaw("this is not a subscribe frame at all");
      consumer.sendRaw("{\"subscribe\":\"" + BETA + "\"}");
      consumer.sendRaw("{\"unsubscribe\":[\"" + BETA + "\"]}");
      assertTrue(
          consumer.isOpen(),
          "an unreadable frame must cost the frame and not the connection — the same stance"
              + " qits-ci's daemon socket takes");
      publish(BETA);
      assertNotNull(
          StoryStream.awaitFrame(consumer, BETA),
          "and the subscription the connection already had is untouched by a frame the server could"
              + " not read");
      StoryStream.drain(consumer);
      story
          .note(
              "three frames the server cannot use — text that is not JSON, JSON whose `subscribe`"
                  + " is not an array, and a verb this protocol does not have — are each dropped"
                  + " with a debug line. The connection stays open AND the subscription it already"
                  + " had is untouched, which the next delivered event is what proves")
          .as("an-unreadable-frame-costs-the-frame-only");

      // --- phase 4: "*" is everything, including names never spoken ------------------------------
      consumer.subscribe("*");
      awaitLive(consumer, GAMMA);
      StoryStream.drain(consumer);
      story
          .note(
              "then [\"*\"], and an event under a signature this consumer has never named reaches"
                  + " it. There is no wildcard matching behind that — the fan-out asks whether the"
                  + " set contains the star or contains the name, and nothing else")
          .as("a-star-means-everything");

      // --- phase 5: the unusable entries are ignored, and replacement is still replacement --------
      //
      // THIS IS THE ONE PHASE WHOSE NEW SET IS A SUBSET OF ITS OLD ONE, so "alpha arrived" would
      // prove nothing at all — the star would have delivered it either way. What proves this frame
      // landed is gamma NO LONGER arriving, so that is what is waited for first.
      consumer.sendRaw("{\"subscribe\":[\"\",42,null,\"" + ALPHA + "\"]}");
      awaitTheStarIsGone(consumer);
      StoryStream.drain(consumer);
      publish(ALPHA);
      assertNotNull(
          StoryStream.awaitFrame(consumer, ALPHA),
          "…and the one name the frame spelled properly is what the connection is now subscribed"
              + " to: the blank, the number and the null were ignored, not fatal");
      StoryStream.drain(consumer);
      publish(GAMMA);
      StoryStream.assertSilent(
          consumer,
          "the frame that named alpha replaced the star, exactly as any other frame would — the"
              + " unusable entries beside it changed nothing about that");
      story
          .note(
              "and a frame carrying a blank string, a number and a null beside one real name is"
                  + " neither refused nor partially applied: the unusable entries are ignored,"
                  + " because the array is a statement of interest and there is no interest a blank"
                  + " string could express. The connection ends up subscribed to the one name it"
                  + " spelled properly — and the star is gone, because replacement is replacement")
          .as("unusable-entries-are-ignored-not-refused");
    }
  }

  /** Publish under {@code signature} until a frame comes back — the only proof this protocol has. */
  private static void awaitLive(FakeSubscriber consumer, String signature) throws Exception {
    // The ids are minted inside, so nothing generated is ever visible to this story's prose.
    long deadline = System.nanoTime() + StoryStream.ARRIVAL.toNanos();
    while (System.nanoTime() < deadline) {
      publish(signature);
      if (StoryStream.awaitFrameOnce(consumer) != null) {
        return;
      }
    }
    throw new AssertionError(
        "no frame arrived for " + signature + " — the subscribe frame never took effect");
  }

  /**
   * Wait until a {@code gamma} publish is <b>not</b> delivered — which is how a narrowing frame is
   * proven applied when the set it replaced was a superset. A publish that still arrives means the
   * connection is on its previous {@code "*"}, so the loop keeps going; one that does not means the
   * frame took, and the assertions after it are about the connection's new set rather than about a
   * frame still in flight.
   */
  private static void awaitTheStarIsGone(FakeSubscriber consumer) throws Exception {
    long deadline = System.nanoTime() + StoryStream.ARRIVAL.toNanos();
    while (System.nanoTime() < deadline) {
      publish(GAMMA);
      if (StoryStream.awaitFrameOnce(consumer) == null) {
        return;
      }
    }
    throw new AssertionError(
        "the narrowing subscribe frame never took effect — gamma is still being delivered, so the"
            + " connection is still on its previous \"*\"");
  }

  private static void publish(String signature) {
    String id = UUID.randomUUID().toString();
    NEVER_IN_THE_BUNDLE.add(id);
    StoryTarget.publisher()
        .body(StoryTarget.envelope(signature, AT, PAYLOAD))
        .when()
        .put(StoryTarget.event(id))
        .then()
        .statusCode(201);
  }

  @AfterAll
  static void theSubscriptionStoryIsComplete() {
    ReportAssertions.assertComplete(CATEGORY_SLUG, SLUG, UserflowReport.PASSED);

    // --- the graph, and it is four arrows for a story of five phases ---------------------------
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        SLUG,
        NetworkEdge.SOCKET,
        CONSUMER,
        StoryTarget.SERVICE,
        "WS " + StoryTarget.STREAM + " subscribe");
    // Every frame of every phase, deduped to one — see the class javadoc.
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        SLUG,
        NetworkEdge.EVENT,
        StoryTarget.SERVICE,
        CONSUMER,
        "EventCreated frame");
    // And every publish, likewise: one caller, one route, one status.
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

    // EXACTLY four. In particular there is no second `socket` arrow: the whole story runs on ONE
    // connection, which is what every rule in it is about — a subscription that survived a
    // reconnect would be a different (and much weaker) claim.
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, SLUG, 4);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY_SLUG, SLUG, List.of(CONSUMER, StoryTarget.OUTBOX, StoryTarget.SERVICE));

    for (String step :
        List.of(
            "one-signature-proven-by-a-frame",
            "subscribe-replaces-it-does-not-add",
            "an-unreadable-frame-costs-the-frame-only",
            "a-star-means-everything",
            "unusable-entries-are-ignored-not-refused")) {
      ReportAssertions.assertStepId(CATEGORY_SLUG, SLUG, step);
    }

    for (String id : NEVER_IN_THE_BUNDLE) {
      ReportAssertions.assertNotLeaked(CATEGORY_SLUG, SLUG, id);
    }
  }
}
