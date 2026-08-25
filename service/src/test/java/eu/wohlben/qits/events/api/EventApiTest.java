package eu.wohlben.qits.events.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.events.control.EventQuery;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * REST round-trips for the events boundary. The addresses are the shipped ones — the suite inherits
 * {@code quarkus.rest.path=/events/api} from main's application.properties rather than re-declaring
 * it — so a change to the segment fails here rather than in a deployment.
 *
 * <p>The manual {@code POST} path only. The publisher's {@code PUT} is a different operation with
 * different semantics and lives in {@link EventPublishApiTest}.
 */
@QuarkusTest
class EventApiTest {

  private String create(String name, String occurredAt, String payload, String description) {
    return create(name, occurredAt, payload, description, null);
  }

  private String create(
      String name, String occurredAt, String payload, String description, String parentId) {
    return create(name, occurredAt, payload, description, parentId, null);
  }

  private String create(
      String name,
      String occurredAt,
      String payload,
      String description,
      String parentId,
      String environment) {
    return given()
        .contentType(ContentType.JSON)
        .body(
            new EventController.CreateEventRequest(
                name,
                occurredAt == null ? null : Instant.parse(occurredAt),
                payload,
                description,
                parentId,
                environment))
        .when()
        .post("/events/api/events")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("event.id", notNullValue())
        .extract()
        .path("event.id");
  }

  @Test
  void createReadDelete() {
    String id =
        create("Deployed qits-events", "2026-07-31T09:00:00Z", "{\"host\":\"one\"}", "First boot");

    given()
        .when()
        .get("/events/api/events/" + id)
        .then()
        .statusCode(200)
        .body("event.name", equalTo("Deployed qits-events"))
        .body("event.occurredAt", equalTo("2026-07-31T09:00:00Z"))
        .body("event.payload", equalTo("{\"host\":\"one\"}"))
        .body("event.description", equalTo("First boot"));

    given()
        .when()
        .delete("/events/api/events/" + id)
        .then()
        .statusCode(200)
        .body("success", equalTo(true));

    given().when().get("/events/api/events/" + id).then().statusCode(404);
  }

  @Test
  void aHandRecordedEventNeedsNoPayload() {
    String id = create("By hand", "2026-07-31T09:00:00Z", null, null);
    given()
        .when()
        .get("/events/api/events/" + id)
        .then()
        .statusCode(200)
        .body("event.payload", nullValue());
  }

  @Test
  void theListIsNewestFirstByWhenItHappened() {
    String prefix = "list-" + System.nanoTime() + "-";
    create(prefix + "middle", "2026-06-01T00:00:00Z", null, null);
    create(prefix + "oldest", "2026-01-01T00:00:00Z", null, null);
    create(prefix + "newest", "2026-12-01T00:00:00Z", null, null);

    given()
        .when()
        .get("/events/api/events")
        .then()
        .statusCode(200)
        .body(
            "events.findAll { it.name.startsWith('" + prefix + "') }.name",
            contains(prefix + "newest", prefix + "middle", prefix + "oldest"));
  }

  @Test
  void theListFilteredByParentIsThatEventsChildrenNewestFirst() {
    // The downward half of a chain walk, and the shape a release train actually has: one event fans
    // out to N. A client cannot do this without listing the whole log, which is the reason the
    // parameter exists — and it is a PARAMETER rather than a route precisely so that no new literal
    // under /events needs a quarkus.quinoa.ignored-path-prefixes entry.
    String prefix = "children-" + System.nanoTime() + "-";
    String parent = create(prefix + "parent", "2026-01-01T00:00:00Z", null, null);
    String stranger = create(prefix + "stranger", "2026-01-01T00:00:00Z", null, null);
    create(prefix + "middle", "2026-06-01T00:00:00Z", null, null, parent);
    create(prefix + "oldest", "2026-02-01T00:00:00Z", null, null, parent);
    create(prefix + "newest", "2026-12-01T00:00:00Z", null, null, parent);
    create(prefix + "somebody-elses", "2026-12-02T00:00:00Z", null, null, stranger);

    given()
        .queryParam("parentId", parent)
        .when()
        .get("/events/api/events")
        .then()
        .statusCode(200)
        .body("events.name", contains(prefix + "newest", prefix + "middle", prefix + "oldest"));
  }

  @Test
  void anUnknownParentIsAnEmptyListRatherThanAFourOhFour() {
    // This log cannot tell "wrong id" from "not here yet" from "another publisher's", and "nothing
    // was caused by it as far as I know" is the true answer in all three cases. A 404 would also
    // make a chain-walking client treat a gap as a failure rather than as the end of the chain.
    given()
        .queryParam("parentId", java.util.UUID.randomUUID().toString())
        .when()
        .get("/events/api/events")
        .then()
        .statusCode(200)
        .body("events", empty());

    // Not even a UUID: still a question with a true answer, and GET stays tolerant of any String id
    // the way it always has — only the publish path demands a canonical one.
    given()
        .queryParam("parentId", "not-a-uuid")
        .when()
        .get("/events/api/events")
        .then()
        .statusCode(200)
        .body("events", empty());
  }

  @Test
  void anEmptyParentIdParameterIsTheWholeLogRatherThanNoLogAtAll() {
    // `?parentId=` is a client that meant to ask for everything and said it clumsily. Blank is
    // absent, which keeps the parameterless behaviour the one default.
    String id = create("Whole log " + System.nanoTime(), "2026-07-31T09:00:00Z", null, null);
    given()
        .queryParam("parentId", "")
        .when()
        .get("/events/api/events")
        .then()
        .statusCode(200)
        .body("events.id", hasItem(id));
  }

  @Test
  void aHandRecordedEventCarriesTheParentIdKeyWhetherOrNotItHasOne() {
    // hasKey rather than nullValue(): an absent JSON path also reads as null, so only the key
    // proves the field is on the wire. A consumer's "does this service know about causation?" is
    // exactly that check.
    String id = create("Rootless " + System.nanoTime(), "2026-07-31T09:00:00Z", null, null);
    given()
        .when()
        .get("/events/api/events/" + id)
        .then()
        .statusCode(200)
        .body("event", hasKey("parentId"))
        .body("event.parentId", nullValue())
        // ... and the tier key rides on the same clause: on the wire even when it is null.
        .body("event", hasKey("environment"))
        .body("event.environment", nullValue());
  }

  /**
   * The rows a paging test needs to see and no others. This suite shares one table with every other
   * test in it — there is no wipe between cases — so each paging case stamps a nonce into the
   * payload and reads its own rows back through {@code ?q=}, which makes the fixture exact and
   * exercises the search at the same time.
   */
  private String nonce() {
    return "nonce-" + System.nanoTime();
  }

  private String payloadWith(String nonce) {
    return "{\"mark\":\"" + nonce + "\"}";
  }

  @Test
  void theNamesRouteAnswersTheVocabularyRatherThanFourOhFouringAsAnId() {
    // /names and /{id} are siblings under one @Path. JAX-RS sorts literal characters ahead of a
    // template, so /events/api/events/names reaches the vocabulary and not EventService.get("names")
    // — a spec guarantee this route leans on, which is exactly why it is pinned here rather than
    // trusted. A regression answers 404 with a JSON body that reads "Event not found: names".
    String name = "Vocabulary" + System.nanoTime();
    create(name, "2026-07-31T09:00:00Z", null, null);

    given()
        .when()
        .get("/events/api/events/names")
        .then()
        .statusCode(200)
        .contentType(ContentType.JSON)
        .body("names", hasItem(name));

    // ... and the template still owns everything that is not the literal.
    given().when().get("/events/api/events/not-an-id").then().statusCode(404);
  }

  @Test
  void theVocabularyHasEachNameOnceAndInOrder() {
    String stem = "Vocab" + System.nanoTime();
    create(stem + "-b", "2026-07-31T09:00:00Z", null, null);
    create(stem + "-b", "2026-07-31T09:00:01Z", null, null);
    create(stem + "-a", "2026-07-31T09:00:02Z", null, null);

    var names =
        given()
            .when()
            .get("/events/api/events/names")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("names", String.class);

    assertEquals(
        java.util.List.of(stem + "-a", stem + "-b"),
        names.stream().filter(n -> n.startsWith(stem)).toList());
    assertEquals(names.stream().distinct().toList(), names, "a vocabulary lists each name once");
    assertEquals(names.stream().sorted().toList(), names, "and in alphabetical order");
  }

  @Test
  void theLimitIsHonouredAndTheCursorWalksTheRestOfTheLog() {
    // The parameter used to be UNKNOWN rather than merely unhonoured: the method declared parentId
    // alone, so JAX-RS dropped `limit` in silence and answered with all of history while the client
    // believed it had asked for five rows.
    String nonce = nonce();
    for (int i = 1; i <= 5; i++) {
      create("Paged " + i, "2026-0" + i + "-01T00:00:00Z", payloadWith(nonce), null);
    }

    var walked = new java.util.ArrayList<String>();
    String cursor = null;
    int pages = 0;
    do {
      var request = given().queryParam("q", nonce).queryParam("limit", 2);
      if (cursor != null) {
        request = request.queryParam("cursor", cursor);
      }
      var body =
          request.when().get("/events/api/events").then().statusCode(200).extract().jsonPath();
      walked.addAll(body.getList("events.name", String.class));
      cursor = body.getString("nextCursor");
      pages++;
    } while (cursor != null);

    assertEquals(
        java.util.List.of("Paged 5", "Paged 4", "Paged 3", "Paged 2", "Paged 1"),
        walked,
        "the cursor must walk every row exactly once, newest first");
    assertEquals(3, pages, "five rows in pages of two is three pages, the last one short");
  }

  @Test
  void aPageBoundaryThatFallsOnATieSplitsItRatherThanLosingASibling() {
    // The fork the live log is full of: two events published by one pipeline run carry the run's
    // finish instant to the microsecond. A scalar `before=<occurredAt>` cursor either skips the
    // second sibling or repeats the first; the composite `<occurredAt>,<id>` resumes after a ROW.
    String nonce = nonce();
    String tie = "2026-08-01T08:52:23.928965Z";
    create("Tie newest", "2026-08-01T09:00:00Z", payloadWith(nonce), null);
    create("Tie fork one", tie, payloadWith(nonce), null);
    create("Tie fork two", tie, payloadWith(nonce), null);
    create("Tie oldest", "2026-08-01T08:00:00Z", payloadWith(nonce), null);

    var first =
        given()
            .queryParam("q", nonce)
            .queryParam("limit", 2)
            .when()
            .get("/events/api/events")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    var ids = new java.util.ArrayList<>(first.getList("events.id", String.class));
    String cursor = first.getString("nextCursor");
    assertNotNull(cursor, "a full page with history behind it must say where to resume");
    // The boundary is inside the tie, which is the case under test: the cursor carries the shared
    // instant AND the id of the sibling already handed out.
    assertTrue(cursor.startsWith(tie + ","), "the boundary must fall on the tie; got " + cursor);

    var second =
        given()
            .queryParam("q", nonce)
            .queryParam("limit", 2)
            .queryParam("cursor", cursor)
            .when()
            .get("/events/api/events")
            .then()
            .statusCode(200)
            .body("nextCursor", nullValue())
            .extract()
            .jsonPath();
    ids.addAll(second.getList("events.id", String.class));

    assertEquals(4, ids.size(), "every row exactly once across the tie: " + ids);
    assertEquals(4, java.util.Set.copyOf(ids).size(), "and none of them twice: " + ids);
  }

  @Test
  void orderAscWalksTheLogOldestFirstFromAWatermark() {
    // The catch-up read: a durable consumer keeps the last row it handled and pages FORWARD from it
    // to the head. Same route, same cursor value, same end-of-log signal — the direction is the only
    // thing that changes, and `desc` (and absent) is byte for byte what it always was.
    // The stem-plus-nonce naming every test here uses: `theVocabularyHasEachNameOnceAndInOrder`
    // reads the WHOLE table's names and compares the database's collation against Java's, and the
    // two disagree about a space inside a name.
    String nonce = nonce();
    String stem = "AscWalk" + System.nanoTime();
    for (int i = 1; i <= 5; i++) {
      create(stem + "-" + i, "2026-0" + i + "-01T00:00:00Z", payloadWith(nonce), null);
    }

    var walked = new java.util.ArrayList<String>();
    String cursor = null;
    int pages = 0;
    do {
      var request = given().queryParam("q", nonce).queryParam("order", "asc").queryParam("limit", 2);
      if (cursor != null) {
        request = request.queryParam("cursor", cursor);
      }
      var body =
          request.when().get("/events/api/events").then().statusCode(200).extract().jsonPath();
      walked.addAll(body.getList("events.name", String.class));
      cursor = body.getString("nextCursor");
      pages++;
    } while (cursor != null);

    assertEquals(
        java.util.List.of(stem + "-1", stem + "-2", stem + "-3", stem + "-4", stem + "-5"),
        walked,
        "ascending must walk every row exactly once, oldest first");
    assertEquals(3, pages, "five rows in pages of two is three pages, the last one short");

    // The same rows the other way round, so a consumer and the SPA cannot disagree about history.
    given()
        .queryParam("q", nonce)
        .queryParam("order", "desc")
        .when()
        .get("/events/api/events")
        .then()
        .statusCode(200)
        .body("events.name", contains(walked.reversed().toArray()));
  }

  @Test
  void anAscendingPageBoundaryThatFallsOnATieSplitsItRatherThanLosingASibling() {
    // The fork the live log is full of, met in the catch-up direction: two events published by one
    // pipeline run carry the run's finish instant to the microsecond. A scalar `after=<occurredAt>`
    // either skips the second sibling or repeats the first — and repeating one is an event handled
    // twice, skipping one an event lost. The composite `<occurredAt>,<id>` resumes after a ROW.
    String nonce = nonce();
    String stem = "AscTie" + System.nanoTime();
    String tie = "2026-08-01T08:52:23.928965Z";
    create(stem + "-oldest", "2026-08-01T08:00:00Z", payloadWith(nonce), null);
    create(stem + "-forkone", tie, payloadWith(nonce), null);
    create(stem + "-forktwo", tie, payloadWith(nonce), null);
    create(stem + "-newest", "2026-08-01T09:00:00Z", payloadWith(nonce), null);

    var first =
        given()
            .queryParam("q", nonce)
            .queryParam("order", "asc")
            .queryParam("limit", 2)
            .when()
            .get("/events/api/events")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    var ids = new java.util.ArrayList<>(first.getList("events.id", String.class));
    String cursor = first.getString("nextCursor");
    assertNotNull(cursor, "a full page with history ahead of it must say where to resume");
    assertTrue(cursor.startsWith(tie + ","), "the boundary must fall on the tie; got " + cursor);

    var second =
        given()
            .queryParam("q", nonce)
            .queryParam("order", "asc")
            .queryParam("limit", 2)
            .queryParam("cursor", cursor)
            .when()
            .get("/events/api/events")
            .then()
            .statusCode(200)
            .body("nextCursor", nullValue())
            .extract()
            .jsonPath();
    ids.addAll(second.getList("events.id", String.class));

    assertEquals(4, ids.size(), "every row exactly once across the tie: " + ids);
    assertEquals(4, java.util.Set.copyOf(ids).size(), "and none of them twice: " + ids);
  }

  @Test
  void ascendingComposesWithTheNameAndSinceFilters() {
    // Catch-up is a filtered read — a consumer subscribes to a handful of names and resumes from a
    // watermark — so the direction composes with the filters rather than replacing them.
    String stem = "AscFilter" + System.nanoTime();
    create(stem + "-build", "2026-07-01T09:00:00Z", null, null);
    create(stem + "-build", "2026-08-01T09:00:00Z", null, null);
    create(stem + "-scm", "2026-08-01T09:00:01Z", null, null);
    create(stem + "-build", "2026-08-01T09:00:02Z", null, null);

    given()
        .queryParam("name", stem + "-build")
        .queryParam("since", "2026-08-01T09:00:00Z")
        .queryParam("order", "asc")
        .when()
        .get("/events/api/events")
        .then()
        .statusCode(200)
        .body("events.occurredAt", contains("2026-08-01T09:00:00Z", "2026-08-01T09:00:02Z"));
  }

  @Test
  void anUnknownOrderIsFourHundredAndAbsentIsNewestFirst() {
    // order is a parameter this service defined, so a misspelling is a client error worth naming.
    // Falling back to descending would answer a catch-up consumer with the head of the log and let
    // it record a watermark it never reached.
    given()
        .queryParam("order", "sideways")
        .when()
        .get("/events/api/events")
        .then()
        .statusCode(400)
        .contentType(ContentType.JSON)
        .body("message", notNullValue());

    String stem = "AscDefault" + System.nanoTime();
    create(stem + "-older", "2026-08-01T09:00:00Z", null, null);
    create(stem + "-newer", "2026-08-01T09:00:01Z", null, null);

    // Absent, blank and `desc` are one answer: the reading this route has always given.
    for (String order : new String[] {null, "", "desc", "DESC"}) {
      var request = given().queryParam("name", stem + "-older," + stem + "-newer");
      if (order != null) {
        request = request.queryParam("order", order);
      }
      request
          .when()
          .get("/events/api/events")
          .then()
          .statusCode(200)
          .body("events.name", contains(stem + "-newer", stem + "-older"));
    }
  }

  @Test
  void thePageEnvelopeIsEventsAndNextCursorAndNothingElse() {
    // The shape the SPA is written against, and it is frozen: `events` and `nextCursor`, with
    // nextCursor null on the last page. No count, no hasMore — the extra row this route fetches
    // answers "is there more" already, and a second way to say it is a second thing to keep true.
    // Adding a field here is a client change in another repository, so it is a decision rather than
    // a convenience.
    var last =
        given()
            .queryParam("q", "no-payload-says-this-" + System.nanoTime())
            .when()
            .get("/events/api/events")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getMap("$");
    assertEquals(java.util.Set.of("events", "nextCursor"), last.keySet());
    assertNull(last.get("nextCursor"), "the last page says so with an explicit null");
  }

  @Test
  void aCursorIsUnderstoodInTheSpellingAClientActuallySendsIt() {
    // Angular's HttpParams codec leaves `,` and `:` unencoded, so the cursor arrives literally —
    // `cursor=2026-08-01T08:52:23.928965Z,<id>` — and a comma-separated `name=A,B` does too.
    // Measured on the deployed client, not assumed. Percent-encoding is equally fine; both decode to
    // one string, and this pins that neither spelling is required.
    String nonce = nonce();
    create("Literal newest", "2026-08-01T09:00:00Z", payloadWith(nonce), null);
    create("Literal oldest", "2026-08-01T08:00:00Z", payloadWith(nonce), null);

    String cursor =
        given()
            .queryParam("q", nonce)
            .queryParam("limit", 1)
            .when()
            .get("/events/api/events")
            .then()
            .statusCode(200)
            .extract()
            .path("nextCursor");
    assertTrue(cursor.contains(",") && cursor.contains(":"), cursor);

    given()
        .urlEncodingEnabled(false)
        .when()
        .get("/events/api/events?q=" + nonce + "&limit=1&cursor=" + cursor)
        .then()
        .statusCode(200)
        .body("events.name", contains("Literal oldest"))
        .body("nextCursor", nullValue());
  }

  @Test
  void aLimitThatIsNotAPageSizeIsFourHundredAndOneAboveTheCapIsClamped() {
    given().queryParam("limit", "0").when().get("/events/api/events").then().statusCode(400);
    given().queryParam("limit", "-1").when().get("/events/api/events").then().statusCode(400);
    given()
        .queryParam("limit", "lots")
        .when()
        .get("/events/api/events")
        .then()
        .statusCode(400)
        .contentType(ContentType.JSON)
        .body("message", notNullValue());

    // The clamp is silent-safe rather than an error: the page says what it holds and whether more
    // exist, so a client asking loosely for 5,000 rows is answered instead of corrected.
    given().queryParam("limit", "5000").when().get("/events/api/events").then().statusCode(200);
  }

  @Test
  void anUnreadableCursorOrSinceIsFourHundredRatherThanAnIgnoredFilter() {
    // Ignoring it would answer with the head of the log, which a paging client reads as the end of
    // history — a wrong answer that looks like a right one.
    given().queryParam("cursor", "no-comma").when().get("/events/api/events").then().statusCode(400);
    given()
        .queryParam("cursor", "yesterday,some-id")
        .when()
        .get("/events/api/events")
        .then()
        .statusCode(400);
    given().queryParam("since", "yesterday").when().get("/events/api/events").then().statusCode(400);
  }

  @Test
  void theNameFilterTakesOneNameOrACommaSeparatedSet() {
    String stem = "Named" + System.nanoTime();
    create(stem + "-build", "2026-08-01T09:00:00Z", null, null);
    create(stem + "-scm", "2026-08-01T09:00:01Z", null, null);
    create(stem + "-software", "2026-08-01T09:00:02Z", null, null);

    given()
        .queryParam("name", stem + "-scm")
        .when()
        .get("/events/api/events")
        .then()
        .statusCode(200)
        .body("events.name", contains(stem + "-scm"));

    // A comma list, and the vocabulary is the stream's — the set a subscribe frame would name.
    given()
        .queryParam("name", stem + "-scm," + stem + "-software")
        .when()
        .get("/events/api/events")
        .then()
        .statusCode(200)
        .body("events.name", contains(stem + "-software", stem + "-scm"));

    given()
        .queryParam("name", "NoSuchName" + System.nanoTime())
        .when()
        .get("/events/api/events")
        .then()
        .statusCode(200)
        .body("events", empty());
  }

  @Test
  void sinceIsAnInclusiveLowerBoundAndTheCursorIsTheOnlyUpperOne() {
    String stem = "Since" + System.nanoTime();
    create(stem + "-before", "2026-07-31T23:59:59Z", null, null);
    create(stem + "-boundary", "2026-08-01T00:00:00Z", null, null);
    create(stem + "-after", "2026-08-01T00:00:01Z", null, null);

    given()
        .queryParam("name", stem + "-before," + stem + "-boundary," + stem + "-after")
        .queryParam("since", "2026-08-01T00:00:00Z")
        .when()
        .get("/events/api/events")
        .then()
        .statusCode(200)
        .body("events.name", contains(stem + "-after", stem + "-boundary"));
  }

  @Test
  void qIsASubstringOfThePayloadAndFindsTheRepositoryUnderEitherKey() {
    // The honest shape of "show me qits-stt": a build names its repository under repoId and a
    // release names it under repository, so there is no one key to filter on and this service parses
    // no payload. Searching the opaque string finds all of them, over-matches slightly, and says so.
    String nonce = nonce();
    create("Q build", "2026-08-01T09:00:00Z", "{\"repoId\":\"" + nonce + "\"}", null);
    create("Q release", "2026-08-01T09:00:01Z", "{\"repository\":\"" + nonce + "\"}", null);
    create("Q package", "2026-08-01T09:00:02Z", "{\"packageName\":\"qits/" + nonce + "\"}", null);
    create("Q elsewhere", "2026-08-01T09:00:03Z", "{\"repoId\":\"somebody-else\"}", null);
    create("Q payloadless", "2026-08-01T09:00:04Z", null, null);

    given()
        .queryParam("q", nonce)
        .when()
        .get("/events/api/events")
        .then()
        .statusCode(200)
        .body("events.name", contains("Q package", "Q release", "Q build"));

    // Case-insensitive, and a miss is an empty page rather than a 404.
    given()
        .queryParam("q", nonce.toUpperCase(java.util.Locale.ROOT))
        .when()
        .get("/events/api/events")
        .then()
        .statusCode(200)
        .body("events", org.hamcrest.Matchers.hasSize(3));

    given()
        .queryParam("q", "no-payload-says-this-" + System.nanoTime())
        .when()
        .get("/events/api/events")
        .then()
        .statusCode(200)
        .body("events", empty())
        .body("nextCursor", nullValue());
  }

  @Test
  void anAttrFilterMatchesAnExactKeyValueFragment() {
    String nonce = nonce();
    create(
        "Attr daemon " + nonce,
        "2026-08-01T09:00:00Z",
        "{\"packageName\":\"qits-ci-daemon-" + nonce + "\",\"packageType\":\"daemon\"}",
        null);
    create(
        "Attr docker " + nonce,
        "2026-08-01T09:00:01Z",
        "{\"packageName\":\"qits-ci-" + nonce + "\",\"packageType\":\"docker\"}",
        null);

    given()
        .queryParam("attr", "packageType=daemon")
        .queryParam("q", nonce)
        .when()
        .get("/events/api/events")
        .then()
        .statusCode(200)
        .body("events.name", contains("Attr daemon " + nonce));
  }

  @Test
  void anAttrFilterMatchesTheWholeValueNotASubstringOfIt() {
    // The closing quote is part of the literal: "dae" is a substring of "daemon", but the pattern
    // this filter builds is `"packageType":"dae"`, which the fragment `"packageType":"daemon"` does
    // not contain. Without the closing quote this would be `?q=` wearing a key name.
    String nonce = nonce();
    create(
        "Attr full value " + nonce,
        "2026-08-01T09:00:00Z",
        "{\"packageType\":\"daemon\",\"mark\":\"" + nonce + "\"}",
        null);

    given()
        .queryParam("attr", "packageType=dae")
        .queryParam("q", nonce)
        .when()
        .get("/events/api/events")
        .then()
        .statusCode(200)
        .body("events", empty());

    given()
        .queryParam("attr", "packageType=daemon")
        .queryParam("q", nonce)
        .when()
        .get("/events/api/events")
        .then()
        .statusCode(200)
        .body("events.name", contains("Attr full value " + nonce));
  }

  @Test
  void twoAttrFiltersAreAndedRatherThanOred() {
    String nonce = nonce();
    create(
        "Attr both " + nonce,
        "2026-08-01T09:00:00Z",
        "{\"packageType\":\"daemon\",\"packageName\":\"qits-ci-daemon-" + nonce + "\"}",
        null);
    create(
        "Attr type only " + nonce,
        "2026-08-01T09:00:01Z",
        "{\"packageType\":\"daemon\",\"packageName\":\"something-else-" + nonce + "\"}",
        null);

    given()
        .queryParam("attr", "packageType=daemon")
        .queryParam("attr", "packageName=qits-ci-daemon-" + nonce)
        .queryParam("q", nonce)
        .when()
        .get("/events/api/events")
        .then()
        .statusCode(200)
        .body("events.name", contains("Attr both " + nonce));
  }

  @Test
  void aMalformedAttrIsFourHundredWithAMessage() {
    given()
        .queryParam("attr", "no-equals-sign")
        .when()
        .get("/events/api/events")
        .then()
        .statusCode(400)
        .contentType(ContentType.JSON)
        .body("message", notNullValue());
  }

  @Test
  void aBlankAttrIsIgnoredRatherThanRejected() {
    // The rule ?parentId= already follows: a client that meant to ask for nothing extra, clumsily.
    given().queryParam("attr", "").when().get("/events/api/events").then().statusCode(200);
  }

  @Test
  void tooManyAttrFiltersIsFourHundred() {
    var request = given();
    for (int i = 0; i <= EventQuery.MAX_ATTR_FILTERS; i++) {
      request = request.queryParam("attr", "k" + i + "=v" + i);
    }
    request.when().get("/events/api/events").then().statusCode(400);
  }

  @Test
  void anAttrFilterComposesWithNameAndLimit() {
    String stem = "AttrCompose" + System.nanoTime();
    create(stem + "-a", "2026-08-01T09:00:00Z", "{\"kind\":\"x\"}", null);
    create(stem + "-b", "2026-08-01T09:00:01Z", "{\"kind\":\"x\"}", null);
    create(stem + "-c", "2026-08-01T09:00:02Z", "{\"kind\":\"y\"}", null);

    given()
        .queryParam("name", stem + "-a," + stem + "-b," + stem + "-c")
        .queryParam("attr", "kind=x")
        .queryParam("limit", 1)
        .when()
        .get("/events/api/events")
        .then()
        .statusCode(200)
        .body("events.name", contains(stem + "-b"));
  }

  @Test
  void theEnvironmentFilterIsAnExactMatchOnTheStampedTier() {
    String stem = "EnvFilter" + System.nanoTime();
    create(stem + "-dev", "2026-08-01T09:00:00Z", null, null, null, "dev");
    create(stem + "-platform", "2026-08-01T09:00:01Z", null, null, null, "platform");
    create(stem + "-untried", "2026-08-01T09:00:02Z", null, null, null, null);
    String allThree = stem + "-dev," + stem + "-platform," + stem + "-untried";

    given()
        .queryParam("name", allThree)
        .queryParam("environment", "dev")
        .when()
        .get("/events/api/events")
        .then()
        .statusCode(200)
        .body("events.name", contains(stem + "-dev"));

    // A tier nothing was published from is an empty page, never an error — and a pre-tier event's
    // null matches no filter value.
    given()
        .queryParam("name", allThree)
        .queryParam("environment", "prod")
        .when()
        .get("/events/api/events")
        .then()
        .statusCode(200)
        .body("events", empty());

    // Blank is absent, the rule every filter here follows.
    given()
        .queryParam("name", allThree)
        .queryParam("environment", "")
        .when()
        .get("/events/api/events")
        .then()
        .statusCode(200)
        .body("events.name", contains(stem + "-untried", stem + "-platform", stem + "-dev"));
  }

  @Test
  void anEnvironmentThatCouldNeverHaveBeenStoredIsFourHundred() {
    given()
        .queryParam("environment", "Not A Slug")
        .when()
        .get("/events/api/events")
        .then()
        .statusCode(400)
        .contentType(ContentType.JSON)
        .body("message", notNullValue());
  }

  @Test
  void parentIdTakesNoneOfTheAttrFilters() {
    // ?parentId= is answered whole and takes none of the list filters — attr included.
    String parent = create("Attr parent " + System.nanoTime(), "2026-08-01T09:00:00Z", null, null);
    create(
        "Attr child",
        "2026-08-01T09:00:01Z",
        "{\"kind\":\"unrelated\"}",
        null,
        parent);

    given()
        .queryParam("parentId", parent)
        .queryParam("attr", "kind=nothing-matches-this")
        .when()
        .get("/events/api/events")
        .then()
        .statusCode(200)
        .body("events.name", contains("Attr child"));
  }

  @Test
  void theChildrenOfOneEventAreAnsweredWholeAndCarryNoCursor() {
    // A parent's children are one per artifact a pipeline declares — bounded by a file in a
    // repository, not by history — so ?parentId= is not paged and says so with an explicit null.
    String parent = create("Fork parent " + System.nanoTime(), "2026-08-01T09:00:00Z", null, null);
    create("Fork child a", "2026-08-01T09:00:01Z", null, null, parent);
    create("Fork child b", "2026-08-01T09:00:01Z", null, null, parent);

    given()
        .queryParam("parentId", parent)
        .queryParam("limit", 1)
        .when()
        .get("/events/api/events")
        .then()
        .statusCode(200)
        .body("events", org.hamcrest.Matchers.hasSize(2))
        .body("$", hasKey("nextCursor"))
        .body("nextCursor", nullValue());
  }

  @Test
  void aBlankNameIsRejectedByBeanValidationBeforeItReachesTheService() {
    given()
        .contentType(ContentType.JSON)
        .body("{\"name\":\"  \"}")
        .when()
        .post("/events/api/events")
        .then()
        .statusCode(400);
  }

  @Test
  void anUnknownEventIsFourOhFourAsJson() {
    // The exception mapper's job: the domain's framework-free NotFoundException carries the status,
    // and the body is JSON — never the SPA's index.html, which is what an unmapped path would give.
    given()
        .when()
        .get("/events/api/events/no-such-event")
        .then()
        .statusCode(404)
        .contentType(ContentType.JSON)
        .body("message", notNullValue());
  }

  @Test
  void thereIsNoUnprefixedForm() {
    // qits-gateway routes verbatim by prefix, so there is nothing to fall back to. If this ever
    // answers, quarkus.rest.path has stopped being applied.
    given().when().get("/api/events").then().statusCode(404);
  }
}
