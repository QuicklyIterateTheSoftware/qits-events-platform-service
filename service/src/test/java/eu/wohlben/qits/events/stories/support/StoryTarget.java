package eu.wohlben.qits.events.stories.support;

import static io.restassured.RestAssured.given;

import eu.wohlben.qits.userflows.Labels;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * The one launched qits-events, as every story in this catalogue addresses it and as every diagram
 * names it.
 *
 * <h2>This service is NOT a leaf, and that is the whole shape of the catalogue</h2>
 *
 * <p>Every sibling repository's catalogue stands a mock where its upstream would be, and its
 * negative claims are about what the service under test <em>dialled</em>. qits-events dials nobody:
 * it has no upstream, its callers are the qits-eventstream jars inside every other service, and the
 * only host it reaches is the postgres its own datasource names. But it is not a leaf either — <b>it
 * pushes</b>, and a pushed frame is an arrow leaving this process that a diagram must be able to
 * draw. So this catalogue has two taps and they see opposite halves of the same connection:
 *
 * <ul>
 *   <li>{@link eu.wohlben.qits.userflows.NetworkTaps#restAssured} — the shipped incoming tap. Every
 *       request a story sends becomes {@code <actor> -> qits-events}, labelled {@code METHOD
 *       <scrubbed path> -> <status>} with the status this service really answered.
 *   <li>{@code stream/FakeSubscriber} — the far side of the socket, and the only code that can see
 *       one. A RestAssured filter cannot see a websocket at all, so the dial, the refused upgrade
 *       and every pushed frame are reported to {@link eu.wohlben.qits.userflows.NetworkCapture} from
 *       inside that client.
 * </ul>
 *
 * <p><b>Which is what makes this catalogue's negative claims mean something.</b> {@code
 * assertNoEdgesTo(<a consumer>)} is not the absence of a tap — it is a tap that was installed,
 * connected and demonstrably receiving at the moment the story says nothing arrived. Every story
 * below that makes such a claim pairs it with a sibling connection (or a sibling story on the very
 * same connection) that <em>did</em> receive, because otherwise "nothing was pushed" and "nobody was
 * listening" are the same sentence.
 *
 * <p><b>{@code assertNoEdgesFrom(SERVICE)} is deliberately used nowhere here.</b> It would be false:
 * this service answers nothing without its store, and every story declares that {@code jdbc} edge
 * (see {@link #STORE}) exactly where it incurs it. A declared edge counts in {@code
 * assertNoEdgesFrom}, and it should — the claim is "nothing left this process", and something did.
 *
 * <h2>One process, one port, three surfaces</h2>
 *
 * <ul>
 *   <li><b>the log's JSON API</b> — {@link #EVENTS} and what hangs off it, under {@code
 *       quarkus.rest.path=/events/api}.
 *   <li><b>the bus</b> — {@link #STREAM}, which does <em>not</em> follow {@code quarkus.rest.path}:
 *       a {@code @WebSocket} path registers straight onto the router and carries the {@code /events}
 *       segment itself, which is why it is spelled in full rather than built from a relative one.
 *   <li><b>the probes</b> — {@link #READY}, at {@code /events/q}, which the shipped tap skips.
 * </ul>
 *
 * <h2>The shipped tap's default skip was checked here</h2>
 *
 * <p>{@code NetworkTaps.restAssured(String)} skips any path carrying a {@code /q/} <b>segment</b>
 * rather than a leading one, which is exactly this service's case: {@code
 * quarkus.http.non-application-root-path=/events/q}, nested under the application root. No route of
 * this service can contain a {@code /q/} segment otherwise — every literal is authored and every
 * free segment is a UUID. So no story class overrides the predicate.
 *
 * <h2>Labels: what survives, what is rewritten, and what must never appear</h2>
 *
 * <p><b>A query string never reaches an incoming label</b> — the shipped tap labels {@code METHOD
 * <scrubbed path> -> <status>} and drops the query entirely. On <em>this</em> service that is the
 * single most load-bearing fact about the diagram, because the whole read model is query
 * parameters: {@code ?name=}, {@code ?order=}, {@code ?cursor=}, {@code ?limit=}, {@code ?attr=},
 * {@code ?environment=}, {@code ?parentId=}. A replay from a watermark, a page of the head of the
 * log and an operator's attribute search are therefore <b>one arrow</b>, told apart by their actor,
 * their status and the notes beside them. That is the right division and not a loss: a cursor is a
 * run-local value, and a label carrying one would move the story's {@code networkHash} on every run.
 *
 * <p><b>Every run-local value this service mints is a UUID</b> — an event id, and a {@code parentId}
 * which is another event's id — and {@link Labels#scrub} already rewrites a UUID in both positions
 * it can appear (a whole path segment, and a query value). So there is nothing left for a normalizer
 * to do, and {@link #NORMALIZER} is claimed as the identity rather than left unset: the slot is a
 * single JVM-wide one, and claiming it from {@link StoryNetwork#install()} is what stops a story
 * class quietly installing a second. {@link #served} runs an assertion's expected label through
 * <em>that same</em> pair of functions in the same order, so an assertion and an observation cannot
 * disagree about what a generated segment became — including if the slot is ever given a job.
 *
 * <p><b>One authored id is deliberately unscrubbable and that is the point.</b> The publish route's
 * "an id that is not a UUID" arm sends the literal {@code not-a-uuid}: a bare number would be
 * rewritten to {@code {id}} and the diagram would show a well-formed id being refused. The literal
 * survives, so the refusal reads as what it is.
 *
 * <h2>What a caller is</h2>
 *
 * <p>Authentication happens at the edge; this service reads {@code X-Qits-User} / {@code
 * X-Qits-Roles} and authenticates nothing. So <b>there is no bearer anywhere in this catalogue</b> —
 * nothing on this wire is a credential in the sense a token is, and there is correspondingly nothing
 * for {@code assertNotLeaked} to protect. What every story does assert with it instead is the other
 * half of the same discipline: the <b>generated event ids</b> a story minted appear in no file of its
 * bundle at all. Notes never interpolate one (a note enters the definition hash) and every label
 * scrubs one, so a leak is precisely the symptom of a hash that will never settle.
 */
public final class StoryTarget {

  private StoryTarget() {}

  /** How every diagram in this catalogue names the service under test. */
  public static final String SERVICE = "qits-events";

  /**
   * The store, as the declared {@code jdbc} edge names it. Not a URL: the address is run-local (an
   * embedded postgres on an ephemeral port) and a declared field is checked against {@link
   * Labels#scrub} rather than scrubbed, so a label with a port in it would be refused outright — and
   * rightly, because it would move the {@code networkHash} every run. The database name is the
   * platform's own derivation, {@code qits_} plus the application name minus its {@code qits-}
   * prefix.
   */
  public static final String STORE = "postgres qits_events";

  // --- the wire paths, spelled in full -------------------------------------------------------

  /** The log's JSON API. {@code quarkus.rest.path} is {@code /events/api}, so this is the whole. */
  public static final String EVENTS = "/events/api/events";

  /** One event, addressed by its id. The tap's label scrubs the UUID to {@code {id}}. */
  public static String event(String id) {
    return EVENTS + "/" + id;
  }

  /** The same route <b>after</b> scrubbing — what an assertion spells, because the id is run-local. */
  public static final String ANY_EVENT = EVENTS + "/{id}";

  /**
   * The vocabulary route: a literal beside the {@code /{id}} template, which JAX-RS sorts ahead of
   * it. {@code qits:admin} only — see {@code OperatorInvestigationIT}, which is where that
   * asymmetry is a story rather than a footnote.
   */
  public static final String NAMES = EVENTS + "/names";

  /**
   * The socket's absolute literal. It does not follow {@code quarkus.rest.path} — a
   * {@code @WebSocket} path carries its own segment — and it is the address a consumer's config
   * derives, so it is spelled in full.
   */
  public static final String STREAM = "/events/stream";

  /** Readiness, which the shipped tap skips — see the class javadoc. */
  public static final String READY = "/events/q/health/ready";

  // --- the identities, as the edge presents them ---------------------------------------------

  /** The headers qits-gateway asserts and strips; qits-auth-core's defaults, unchanged here. */
  public static final String USER_HEADER = "X-Qits-User";

  public static final String ROLES_HEADER = "X-Qits-Roles";

  /** The machine role a sibling service's eventstream jar carries, and the only one that publishes. */
  public static final String SYSTEM_ROLE = "qits:system";

  /** The person's role. It reads every page of the events client, and it writes no event. */
  public static final String ADMIN_ROLE = "qits:admin";

  /** A real publisher: qits-ci's outbox is the busiest one on the platform. */
  public static final String PUBLISHER = "qits-ci";

  /** The person behind the events client, as the edge names them. */
  public static final String PERSON = "alice";

  /** A sibling service's outbox: the machine role, and a JSON body on every write. */
  public static RequestSpecification publisher() {
    return given()
        .header(USER_HEADER, PUBLISHER)
        .header(ROLES_HEADER, SYSTEM_ROLE)
        .contentType(ContentType.JSON);
  }

  /** The same service reading its own catch-up — the list route takes the machine role too. */
  public static RequestSpecification consumer() {
    return given().header(USER_HEADER, PUBLISHER).header(ROLES_HEADER, SYSTEM_ROLE);
  }

  /** A person at the events client: named by the edge, holding the role that reads. */
  public static RequestSpecification operator() {
    return given().header(USER_HEADER, PERSON).header(ROLES_HEADER, ADMIN_ROLE);
  }

  /** The same person, sending a body — the manual {@code POST} record path. */
  public static RequestSpecification operatorWriting() {
    return operator().contentType(ContentType.JSON);
  }

  /** The headers identity travels as on an UPGRADE, which is where the socket's door is. */
  public static Map<String, String> machineCredential() {
    return Map.of(USER_HEADER, PUBLISHER, ROLES_HEADER, SYSTEM_ROLE);
  }

  /** The same upgrade as a person: the socket takes {@code qits:admin} too, and only these two. */
  public static Map<String, String> personCredential() {
    return Map.of(USER_HEADER, PERSON, ROLES_HEADER, ADMIN_ROLE);
  }

  // --- the initiators the wire cannot tell apart ----------------------------------------------
  //
  // On this service four of these differ by TWO HEADERS and nothing else, and three of them reach
  // the same route. NetworkCapture.actor(...) is the only thing that keeps them apart on a diagram,
  // and the framework resets it to a default at every story start so nothing leaks between stories.

  /** A sibling service's outbox — the busiest caller on the platform, and the only one that writes. */
  public static final String OUTBOX = "a publisher's outbox";

  /** A sibling service's eventstream jar: it listens, and it reads its own catch-up over HTTP. */
  public static final String DURABLE_CONSUMER = "a durable consumer";

  /**
   * A person at the events client. <b>One name, everywhere</b>: the same session refuses to publish
   * in one story and investigates a chain in another, and two names for one caller would draw two
   * nodes on the aggregate diagram for something the platform has one of.
   */
  public static final String PERSON_SESSION = "a person's session";

  /** A caller the edge never named — no {@code X-Qits-User} at all, which is a 401 and not a 403. */
  public static final String UNNAMED = "an unnamed caller";

  /** The same absence at the socket's handshake, where it is a refused upgrade. */
  public static final String UNNAMED_CONSUMER = "an unnamed consumer";

  // --- envelopes ------------------------------------------------------------------------------

  /**
   * The publish envelope, assembled as TEXT rather than from an object, because what is under test
   * includes the wire: {@code payload} is canonical JSON <em>inside a string</em> and reaches the
   * server escaped exactly as a publisher escapes it. This server stores and compares those bytes
   * verbatim and never parses, reformats or reorders them — the publisher canonicalizes, and a
   * server that pretty-printed the value would break the byte-for-byte equality the idempotent PUT
   * rests on.
   */
  public static String envelope(String name, String occurredAt, String payload) {
    return envelope(name, occurredAt, payload, null, "platform");
  }

  /**
   * The same envelope, optionally naming the event that caused this one and the tier the publisher
   * ran in. A null parent is left off the wire entirely rather than sent as {@code null}:
   * absent-means-null is the contract's one backward-compatibility clause — the bytes an older
   * publisher sends — and it is the shape most of the fleet still publishes in.
   */
  public static String envelope(
      String name, String occurredAt, String payload, String parentId, String environment) {
    return "{\"name\":\""
        + name
        + "\",\"occurredAt\":\""
        + occurredAt
        + "\",\"payload\":\""
        + escape(payload)
        + "\",\"description\":null"
        + (parentId == null ? "" : ",\"parentId\":\"" + parentId + "\"")
        + (environment == null ? "" : ",\"environment\":\"" + environment + "\"")
        + "}";
  }

  /** The manual record path's body: {@code POST} invents the id and may omit {@code occurredAt}. */
  public static String record(String name, String payload) {
    return "{\"name\":\"" + name + "\",\"payload\":\"" + escape(payload) + "\"}";
  }

  /** JSON-in-a-string, as a publisher escapes it. */
  private static String escape(String payload) {
    return payload.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  // --- what an assertion has to spell ---------------------------------------------------------

  /**
   * The single JVM-wide label-normalizer slot this catalogue claims, and it is the <b>identity</b>
   * on purpose — see the class javadoc: every generated value on this service's surface is a UUID,
   * which {@link Labels#scrub} already rewrites in both positions it can appear, and every other
   * distinction lives in a query string the tap never puts in a label. Claiming the slot is what
   * makes that a decision instead of an omission, and routing {@link #served} through the same
   * constant is what keeps an assertion and an observation from disagreeing if it is ever given a
   * job.
   */
  static final UnaryOperator<String> NORMALIZER = UnaryOperator.identity();

  /**
   * The label the shipped RestAssured tap gives an incoming request: {@code METHOD <path> ->
   * <status>}, run through the default scrubber and then through the normalizer, in that order —
   * the same two functions, in the same order, {@code NetworkCapture} applies on the way in.
   */
  public static String served(String method, String path, int status) {
    return NORMALIZER.apply(Labels.scrub(method + " " + path + " -> " + status));
  }

  public static String read(String path, int status) {
    return served("GET", path, status);
  }

  public static String published(int status) {
    return served("PUT", ANY_EVENT, status);
  }

  public static String posted(String path, int status) {
    return served("POST", path, status);
  }

  public static String deleted(String path, int status) {
    return served("DELETE", path, status);
  }
}
