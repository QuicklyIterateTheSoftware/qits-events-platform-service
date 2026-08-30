package eu.wohlben.qits.events.stories.replay;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.events.stories.support.StoryNetwork;
import eu.wohlben.qits.events.stories.support.StoryProfile;
import eu.wohlben.qits.events.stories.support.StoryTarget;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.Network;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.Slugs;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.json.JsonPath;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * <b>Replay on this platform is not a socket feature, and that is a decision rather than a gap.</b>
 * {@code /events/stream} is live only: no replay, no offset, no catch-up, no "start from" frame. The
 * envelope carries the row's id precisely so that catch-up could be built without breaking anybody,
 * and it was — as the <em>log's own route</em>, {@code GET /events/api/events?order=asc}, which is
 * what this story is about.
 *
 * <p>The consequence is worth saying plainly, because a reader arrives expecting a broker: <b>a
 * consumer's position is the consumer's own property.</b> This service stores no offsets, no consumer
 * groups and no acknowledgements. A cursor is not a token it remembers — it is the page's own last
 * row, so nothing expires and a consumer that kept one for a week resumes exactly where it stopped.
 * An append-only log is what makes that safe: rows arrive at the head, so a walk forward can be
 * overtaken but never invalidated.
 *
 * <h2>The tie is the whole reason the cursor is a pair</h2>
 *
 * <p>{@code occurredAt} is not unique and cannot be made unique — a pipeline run's events carry the
 * run's finish instant, so a fork's siblings tie to the microsecond by construction. This story
 * publishes such a tie deliberately and pages through it <b>one row at a time</b>, which is the
 * shape that breaks a scalar {@code ?before=<occurredAt>} cursor: it either hands the same sibling
 * out twice or drops one, and both are silent. What is asserted is therefore not an internal
 * ordering (the tie's own order is the database's collation and this story does not own it) but the
 * two properties a consumer actually depends on: every row exactly once, and the descending reading
 * being the exact reverse of the ascending one.
 *
 * <h2>Where the diagram is thin, on purpose</h2>
 *
 * <p>Three arrows: the publishes, the reads, and the store. There is no {@code socket} edge here at
 * all, and that absence <em>is</em> the architectural statement — a replaying consumer holds no
 * connection, asks for pages over HTTP, and only dials the stream once it has caught up.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
public class ReplayFromTheLogIT {

  static final String CATEGORY = "replay";

  static final String CATEGORY_SLUG = Slugs.slug(CATEGORY);

  static final String STORY =
      "A consumer that has never run replays the whole log, forward and one page at a time";

  static final String SLUG = Slugs.slug(STORY);

  /** This story's own vocabulary, so no other story's rows can appear in any page below. */
  private static final String SIGNATURE = "UserflowReplayStep";

  private static final String FIRST_AT = "2026-08-27T10:00:00Z";

  /** The tie: two rows at one instant, which is what the composite cursor exists for. */
  private static final String TIED_AT = "2026-08-27T11:00:00Z";

  private static final String LAST_AT = "2026-08-27T12:00:00Z";

  private static final String PAYLOAD = "{\"repository\":\"qits-events\"}";

  /** The initiator: a consumer with no watermark at all, which is the from-zero case. */
  private static final String NEWCOMER = "a consumer replaying from zero";

  private static final List<String> NEVER_IN_THE_BUNDLE = new ArrayList<>();

  @BeforeAll
  static void tapTheBus() {
    StoryNetwork.install();
  }

  @UserStory(value = STORY, category = CATEGORY)
  @UserStoryDescription(
      """
      A new consumer is deployed. It has never run, so it holds no watermark, and the stream
      would only ever tell it what happens NEXT — the socket is live only, with no replay and no
      offset in the protocol at all. So it replays the log itself, over the route that exists for
      exactly this.

      It reads ascending from nothing, one row at a time, following each page's `nextCursor`
      until a page comes back with none. Four rows come out, each exactly once — and two of them
      happened at the SAME instant, which is not a contrivance: a pipeline run's events carry the
      run's finish instant, so siblings tie by construction. The cursor is the pair
      `<occurredAt>,<id>` for that reason, and a scalar cursor over the instant alone would hand
      one sibling out twice or drop the other, silently, exactly at a page boundary.

      Then it resumes from a cursor it chose — the one it happened to be holding after two pages
      — and gets the rest of the log and nothing it had already seen. That is the same operation
      a restarted consumer performs with its watermark; "from zero" and "from an offset" are one
      route with one parameter.

      It reads the same rows descending, and gets the exact reverse: both halves of the
      comparison turn round with the sort, so the two readings are one order seen from its two
      ends rather than two orderings that happen to agree.

      And it reads past the head, which is a thing a catch-up consumer does on every poll: an
      empty page and a null `nextCursor`. Not a 404, not an error — the end of a log is a fact
      about the log, and it is the only end-of-log signal there is, because a full page is not
      one.
      """)
  void aConsumerReplaysTheWholeLogForwardOnePageAtATime(Interactions story, Network net) {
    net.declare(
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "select event ordered by (occurredAt, id), paged by the composite cursor");

    // --- the log this story replays -------------------------------------------------------------
    NetworkCapture.actor(StoryTarget.OUTBOX);
    String first = publish(FIRST_AT);
    String tiedA = publish(TIED_AT);
    String tiedB = publish(TIED_AT);
    String last = publish(LAST_AT);
    story
        .note(
            "four events are published, and two of them happened at the SAME instant — not a"
                + " contrivance but the normal case, because a pipeline run's events all carry the"
                + " run's finish instant and a fork's siblings therefore tie to the microsecond")
        .as("a-log-with-a-tie-in-it");

    // --- from zero, one row at a time -----------------------------------------------------------
    NetworkCapture.actor(NEWCOMER);
    List<String> ascending = new ArrayList<>();
    String cursor = null;
    String afterTwoPages = null;
    for (int page = 1; page <= 10; page++) {
      JsonPath answer = readAscending(cursor, 1);
      List<String> ids = answer.getList("events.id");
      ascending.addAll(ids);
      cursor = answer.getString("nextCursor");
      if (page == 2) {
        // The watermark a consumer would have kept if it had stopped here. It is the page's own
        // last row and nothing this service stored, which is why it never expires.
        afterTwoPages = cursor;
      }
      if (cursor == null) {
        break;
      }
    }
    assertEquals(
        4, ascending.size(), "paging one row at a time hands out every row exactly once");
    assertEquals(
        Set.of(first, tiedA, tiedB, last),
        new HashSet<>(ascending),
        "…and exactly those rows, with no repeat across the tie's page boundary");
    assertEquals(first, ascending.get(0), "ascending starts at the oldest row");
    assertEquals(last, ascending.get(3), "…and ends at the newest");
    assertEquals(
        Set.of(tiedA, tiedB),
        new HashSet<>(ascending.subList(1, 2 + 1)),
        "the two rows that tie are the two in the middle, and the pages did not interleave them"
            + " with anything else");
    assertTrue(afterTwoPages != null, "a page that is not the last names where the next resumes");
    story
        .note(
            "reading ascending from nothing, one row at a time, hands out every row exactly once —"
                + " the tie included. A scalar cursor over the instant alone would repeat one"
                + " sibling or drop the other right at that page boundary, silently, which is what"
                + " the composite <occurredAt>,<id> pair exists to prevent")
        .as("every-row-exactly-once-tie-included");

    // --- from a chosen offset: the same route, one parameter --------------------------------------
    JsonPath resumed = readAscending(afterTwoPages, 200);
    assertEquals(
        ascending.subList(2, 4),
        resumed.getList("events.id"),
        "resuming from a cursor gives the rest of the log, in the same order, and nothing already"
            + " handled");
    story
        .note(
            "then it resumes from a cursor it chose and gets the rest of the log and nothing it had"
                + " already seen. That is the same operation a restarted consumer performs with its"
                + " watermark — 'from zero' and 'from an offset' are one route with one parameter,"
                + " and the cursor is the page's own last row rather than a token this service"
                + " remembers, so nothing expires")
        .as("and-from-a-chosen-offset");

    // --- the same rows, the other way round -------------------------------------------------------
    List<String> descending =
        StoryTarget.consumer()
            .queryParam("name", SIGNATURE)
            .when()
            .get(StoryTarget.EVENTS)
            .then()
            .statusCode(200)
            .body("events", hasSize(4))
            .body("nextCursor", nullValue())
            .extract()
            .jsonPath()
            .getList("events.id");
    List<String> reversed = new ArrayList<>(ascending);
    java.util.Collections.reverse(reversed);
    assertEquals(
        reversed,
        descending,
        "descending is the EXACT reverse of ascending: both halves of the comparison turn round"
            + " with the sort, tie and all");
    story
        .note(
            "the same rows read the other way round come back in the exact reverse order. The"
                + " sort's id half turns round with its instant half — an ascending instant beside"
                + " a descending id would still be a total order and would still skip rows inside a"
                + " tie, which is the one failure the composite cursor exists to prevent")
        .as("one-order-seen-from-both-ends");

    // --- past the head, which a catch-up consumer does on every poll -------------------------------
    JsonPath head = readAscending(LAST_AT + "," + last, 200);
    assertTrue(head.getList("events").isEmpty(), "past the head there is nothing left to hand out");
    assertEquals(
        null, head.getString("nextCursor"), "and the answer names no next page, because there is none");
    story
        .note(
            "and reading past the head — which a catch-up consumer does on every poll — is an empty"
                + " page and a null nextCursor. Not a 404 and not an error: the end of a log is a"
                + " fact about the log, and a null nextCursor is the ONLY end-of-log signal there"
                + " is, because a full page is not one")
        .as("the-end-of-the-log-is-a-null-cursor");

    story
        .note(
            "none of this touched the stream. The socket is live only — no replay, no offset, no"
                + " 'start from' frame — and the envelope carries the row's id precisely so that"
                + " catch-up could be built beside it without breaking anybody. A consumer's"
                + " position is the CONSUMER's property: this service stores no offsets, no groups"
                + " and no acknowledgements")
        .as("replay-is-the-logs-route-not-the-sockets");
  }

  /** One ascending page, filtered to this story's vocabulary. */
  private static JsonPath readAscending(String cursor, int limit) {
    var request =
        StoryTarget.consumer()
            .queryParam("name", SIGNATURE)
            .queryParam("order", "asc")
            .queryParam("limit", limit);
    if (cursor != null) {
      request = request.queryParam("cursor", cursor);
    }
    return request.when().get(StoryTarget.EVENTS).then().statusCode(200).extract().jsonPath();
  }

  /** One create, under an id the publisher chose. Returned so the story can compare, never print. */
  private static String publish(String occurredAt) {
    String id = UUID.randomUUID().toString();
    NEVER_IN_THE_BUNDLE.add(id);
    StoryTarget.publisher()
        .body(StoryTarget.envelope(SIGNATURE, occurredAt, PAYLOAD))
        .when()
        .put(StoryTarget.event(id))
        .then()
        .statusCode(201)
        .body("event.id", notNullValue());
    return id;
  }

  @AfterAll
  static void theReplayStoryIsComplete() {
    ReportAssertions.assertComplete(CATEGORY_SLUG, SLUG, UserflowReport.PASSED);

    // --- the graph: three arrows for eight requests ---------------------------------------------
    // Four publishes, one edge. Seven reads — four pages, a resume, a descending read and a read
    // past the head — one edge, because they differ ONLY in a query string and a query string never
    // reaches a label. That is deliberate: a cursor is run-local and would move this story's
    // networkHash on every run.
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        SLUG,
        NetworkEdge.HTTP,
        StoryTarget.OUTBOX,
        StoryTarget.SERVICE,
        StoryTarget.published(201));
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        SLUG,
        NetworkEdge.HTTP,
        NEWCOMER,
        StoryTarget.SERVICE,
        StoryTarget.read(StoryTarget.EVENTS, 200));
    ReportAssertions.assertDeclaredEdge(
        CATEGORY_SLUG,
        SLUG,
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "select event ordered by (occurredAt, id), paged by the composite cursor");

    // EXACTLY three, and the shape of that number is the story: no `socket` arrow and no `event`
    // arrow anywhere. A replaying consumer holds no connection and is pushed nothing — it asks.
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, SLUG, 3);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY_SLUG, SLUG, List.of(StoryTarget.OUTBOX, NEWCOMER, StoryTarget.SERVICE));

    for (String step :
        List.of(
            "a-log-with-a-tie-in-it",
            "every-row-exactly-once-tie-included",
            "and-from-a-chosen-offset",
            "one-order-seen-from-both-ends",
            "the-end-of-the-log-is-a-null-cursor",
            "replay-is-the-logs-route-not-the-sockets")) {
      ReportAssertions.assertStepId(CATEGORY_SLUG, SLUG, step);
    }

    for (String id : NEVER_IN_THE_BUNDLE) {
      ReportAssertions.assertNotLeaked(CATEGORY_SLUG, SLUG, id);
    }
  }
}
