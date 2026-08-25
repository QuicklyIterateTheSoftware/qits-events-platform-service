package eu.wohlben.qits.events.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@code PUT /events/api/events/{id}} — the bus's publish, over the shipped addresses.
 *
 * <p>Three answers and no fourth: 201 for an id this log has not seen, 200 for the same event
 * arriving twice, 400 for a UUID that has been reused for something else. The bodies here are raw
 * JSON strings rather than the request record, because what a publisher in another repository sends
 * is bytes on a wire and the point is to prove this service reads <em>those</em> — including a
 * {@code payload} that is a JSON document escaped inside a JSON string, which is the shape most
 * easily broken by a well-meaning change on either side.
 */
@QuarkusTest
class EventPublishApiTest {

  private static final String PAYLOAD =
      "{\\\"branch\\\":\\\"main\\\",\\\"commitSha\\\":\\\"abc123\\\",\\\"repoId\\\":\\\"qits-ci\\\"}";

  /** The envelope as a publisher that knows about causation sends it — {@code parentId} always present. */
  private static String envelope(String name, String occurredAt, String payload, String parentId) {
    return "{\"name\":\""
        + name
        + "\",\"occurredAt\":\""
        + occurredAt
        + "\",\"payload\":"
        + (payload == null ? "null" : "\"" + payload + "\"")
        + ",\"description\":null,\"parentId\":"
        + (parentId == null ? "null" : "\"" + parentId + "\"")
        + "}";
  }

  /** The whole envelope as today's publisher sends it — {@code environment} always present too. */
  private static String envelope(
      String name, String occurredAt, String payload, String parentId, String environment) {
    return "{\"name\":\""
        + name
        + "\",\"occurredAt\":\""
        + occurredAt
        + "\",\"payload\":"
        + (payload == null ? "null" : "\"" + payload + "\"")
        + ",\"description\":null,\"parentId\":"
        + (parentId == null ? "null" : "\"" + parentId + "\"")
        + ",\"environment\":"
        + (environment == null ? "null" : "\"" + environment + "\"")
        + "}";
  }

  /**
   * The envelope <b>without</b> the field at all — what a publisher built against the five-field
   * contract sends, and what this service must go on accepting. That compatibility clause is the
   * reason this side ships before any publisher that stamps.
   */
  private static String envelope(String name, String occurredAt, String payload) {
    return "{\"name\":\""
        + name
        + "\",\"occurredAt\":\""
        + occurredAt
        + "\",\"payload\":"
        + (payload == null ? "null" : "\"" + payload + "\"")
        + ",\"description\":null}";
  }

  private static io.restassured.response.Response put(String id, String body) {
    return given().contentType(ContentType.JSON).body(body).when().put("/events/api/events/" + id);
  }

  @Test
  void anUnknownIdIsCreatedAndAnsweredTwoOhOne() {
    String id = UUID.randomUUID().toString();
    put(id, envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD))
        .then()
        .statusCode(201)
        .body("event.id", equalTo(id))
        .body("event.name", equalTo("BuildSuccessful"))
        .body("event.occurredAt", equalTo("2026-07-31T12:46:03Z"))
        // Verbatim: the canonical JSON the publisher produced, handed back unreformatted.
        .body(
            "event.payload",
            equalTo("{\"branch\":\"main\",\"commitSha\":\"abc123\",\"repoId\":\"qits-ci\"}"))
        .body("event.createdAt", notNullValue());
  }

  @Test
  void theSameBytesAgainAreAnsweredTwoHundredAndWriteNothing() {
    String id = UUID.randomUUID().toString();
    String body = envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD);
    String createdAt = put(id, body).then().statusCode(201).extract().path("event.createdAt");

    // A publisher whose first attempt got no answer sends exactly this again.
    put(id, body)
        .then()
        .statusCode(200)
        .body("event.id", equalTo(id))
        // Nothing was written, so the row's own timestamp is still the first attempt's.
        .body("event.createdAt", equalTo(createdAt));
  }

  @Test
  void aReusedUuidIsFourHundredWhateverDiffers() {
    String id = UUID.randomUUID().toString();
    put(id, envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD)).then().statusCode(201);

    // The caller reused a UUID. Unretryable, so 400 rather than a conflict to sit and poll on —
    // and the answer is this context's JSON error, never the client's index.html.
    put(id, envelope("BuildSuccessful", "2026-07-31T12:46:03Z", "{\\\"branch\\\":\\\"other\\\"}"))
        .then()
        .statusCode(400)
        .contentType(ContentType.JSON)
        .body("message", notNullValue());
    put(id, envelope("SomethingElse", "2026-07-31T12:46:03Z", PAYLOAD)).then().statusCode(400);
    put(id, envelope("BuildSuccessful", "2026-07-31T12:46:04Z", PAYLOAD)).then().statusCode(400);
    put(id, envelope("BuildSuccessful", "2026-07-31T12:46:03Z", null)).then().statusCode(400);
  }

  @Test
  void anIdThatIsNotAUuidIsFourHundred() {
    // The id is the idempotency key, so a caller that cannot spell one has no retry-safe identity
    // to offer. GET and DELETE stay tolerant of any String id — see EventApiTest.
    put("not-a-uuid", envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD))
        .then()
        .statusCode(400)
        .contentType(ContentType.JSON);
    put("1-1-1-1-1", envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD))
        .then()
        .statusCode(400);
  }

  @Test
  void anEnvelopeMissingItsRequiredFieldsIsFourHundred() {
    String id = UUID.randomUUID().toString();
    // occurredAt is required here and optional on POST: it is one of the three fields a replay is
    // compared on, so a time this server invented would make the event unable to replay as itself.
    put(id, "{\"name\":\"BuildSuccessful\"}").then().statusCode(400);
    put(id, "{\"occurredAt\":\"2026-07-31T12:46:03Z\"}").then().statusCode(400);
    put(id, "{\"name\":\"  \",\"occurredAt\":\"2026-07-31T12:46:03Z\"}").then().statusCode(400);
  }

  @Test
  void aPublishedEventNeedNotCarryAPayload() {
    String id = UUID.randomUUID().toString();
    String body = envelope("SomethingHappened", "2026-07-31T12:46:03Z", null);
    put(id, body).then().statusCode(201).body("event.payload", nullValue());
    put(id, body).then().statusCode(200);
  }

  @Test
  void aPublishMayNameTheEventThatCausedIt() {
    String parent = UUID.randomUUID().toString();
    String id = UUID.randomUUID().toString();
    String body = envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD, parent);

    put(id, body).then().statusCode(201).body("event.parentId", equalTo(parent));
    // ... and the same bytes again are the same event, cause and all.
    put(id, body).then().statusCode(200).body("event.parentId", equalTo(parent));

    given()
        .when()
        .get("/events/api/events/" + id)
        .then()
        .statusCode(200)
        .body("event.parentId", equalTo(parent));
  }

  @Test
  void theParentIdKeyIsPresentEvenWhenThereIsNoParent() {
    // hasKey, not nullValue(): a JSON path that is ABSENT also reads as null, and the difference is
    // the whole of what a client can rely on. A consumer checking "does this service know about
    // causation?" looks for the key, so an omit-nulls mapper here would be a silent contract break
    // that every assertion phrased as nullValue() would go on passing through.
    String id = UUID.randomUUID().toString();
    put(id, envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD, null))
        .then()
        .statusCode(201)
        .body("event", hasKey("parentId"))
        .body("event.parentId", nullValue());

    given().when().get("/events/api/events/" + id).then().body("event", hasKey("parentId"));
  }

  @Test
  void anEnvelopeThatNeverLearnedAboutTheFieldIsStillAccepted() {
    // The contract's one backward-compatibility clause: absent is legal and means null. A publisher
    // built against the five-field envelope keeps working, which is what makes it safe to ship this
    // service before the publishers that stamp — and it is one-directional, so the other order
    // (a stamping publisher against an events service without the column) is the one that loses
    // parents silently.
    String id = UUID.randomUUID().toString();
    String body = envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD);
    put(id, body).then().statusCode(201).body("event.parentId", nullValue());
    put(id, body).then().statusCode(200);
    // Absent and an explicit null are the same statement, so one replays as the other.
    put(id, envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD, null))
        .then()
        .statusCode(200);
  }

  @Test
  void reParentingOneIdIsFourHundredInBothDirections() {
    // parentId is INSIDE the comparison, unlike description. Two PUTs of one id claiming different
    // causes are two different claims about history; kept outside, the server would keep the first
    // and answer 200 while the publisher believed it had published the second — two services
    // disagreeing about the shape of history with no error anywhere.
    String first = UUID.randomUUID().toString();
    String parent = UUID.randomUUID().toString();
    put(first, envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD, null))
        .then()
        .statusCode(201);
    // null → set
    put(first, envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD, parent))
        .then()
        .statusCode(400)
        .contentType(ContentType.JSON)
        .body("message", notNullValue());

    String second = UUID.randomUUID().toString();
    put(second, envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD, parent))
        .then()
        .statusCode(201);
    // set → null, and set → a different one
    put(second, envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD, null))
        .then()
        .statusCode(400);
    put(
            second,
            envelope(
                "BuildSuccessful",
                "2026-07-31T12:46:03Z",
                PAYLOAD,
                UUID.randomUUID().toString()))
        .then()
        .statusCode(400);
  }

  @Test
  void aPublishMayNameTheTierItRanIn() {
    String id = UUID.randomUUID().toString();
    String body = envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD, null, "dev");

    put(id, body).then().statusCode(201).body("event.environment", equalTo("dev"));
    // ... and the same bytes again are the same event, tier and all.
    put(id, body).then().statusCode(200).body("event.environment", equalTo("dev"));

    given()
        .when()
        .get("/events/api/events/" + id)
        .then()
        .statusCode(200)
        .body("event.environment", equalTo("dev"));
  }

  @Test
  void theEnvironmentKeyIsPresentEvenWhenThereIsNoTier() {
    // The same hasKey clause parentId carries, for the same reason: absent also reads as null, and
    // a consumer probing "does this service know about tiers?" looks for the key itself.
    String id = UUID.randomUUID().toString();
    put(id, envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD, null))
        .then()
        .statusCode(201)
        .body("event", hasKey("environment"))
        .body("event.environment", nullValue());

    given().when().get("/events/api/events/" + id).then().body("event", hasKey("environment"));
  }

  @Test
  void reTieringOneIdIsFourHundredInBothDirections() {
    // environment is INSIDE the comparison, like parentId: one id claiming two tiers is two
    // different claims about history.
    String first = UUID.randomUUID().toString();
    put(first, envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD, null, null))
        .then()
        .statusCode(201);
    // null → set
    put(first, envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD, null, "dev"))
        .then()
        .statusCode(400)
        .contentType(ContentType.JSON)
        .body("message", notNullValue());

    String second = UUID.randomUUID().toString();
    put(second, envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD, null, "dev"))
        .then()
        .statusCode(201);
    // set → null, and set → a different one
    put(second, envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD, null, null))
        .then()
        .statusCode(400);
    put(second, envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD, null, "platform"))
        .then()
        .statusCode(400);
  }

  @Test
  void anEnvironmentThatIsNotADnsSafeNameIsFourHundred() {
    // Shape, not existence: whether 'dev' exists is deliberately never asked, but 'Dev' could never
    // have been an environment at all.
    put(
            UUID.randomUUID().toString(),
            envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD, null, "Not A Slug"))
        .then()
        .statusCode(400)
        .contentType(ContentType.JSON)
        .body("message", notNullValue());
  }

  @Test
  void aParentThatIsNotAUuidIsFourHundred() {
    put(
            UUID.randomUUID().toString(),
            envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD, "not-a-uuid"))
        .then()
        .statusCode(400)
        .contentType(ContentType.JSON);
    // UUID.fromString alone accepts this; the round-trip check is what does not.
    put(
            UUID.randomUUID().toString(),
            envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD, "1-1-1-1-1"))
        .then()
        .statusCode(400);
  }

  @Test
  void anEventMayNotCauseItself() {
    // Decidable from a single row, with no graph to consult — malformed input in the same sense a
    // non-UUID is. Note what is NOT here: no cycle guard. One that caught only the self-edge would
    // be worse than none, because it cannot see A → B → A while telling a reader cycles are handled.
    String id = UUID.randomUUID().toString();
    put(id, envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD, id))
        .then()
        .statusCode(400)
        .contentType(ContentType.JSON)
        .body("message", notNullValue());
    given().when().get("/events/api/events/" + id).then().statusCode(404);
  }

  @Test
  void aParentThisLogHasNeverSeenIsAcceptedRatherThanRefused() {
    // Nothing orders a parent's arrival before its child's, and 400 is unretryable — so an
    // existence check would turn a publisher's timing accident into permanent data loss. A dangling
    // parent is data; the reader treats it as the start of the chain.
    String neverSeen = UUID.randomUUID().toString();
    String id = UUID.randomUUID().toString();
    put(id, envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD, neverSeen))
        .then()
        .statusCode(201)
        .body("event.parentId", equalTo(neverSeen));
    given().when().get("/events/api/events/" + neverSeen).then().statusCode(404);
  }
}
