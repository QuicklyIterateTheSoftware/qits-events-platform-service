package eu.wohlben.qits.events.stories.operations;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * <b>The log is written by services and read by people, and this is the reading half.</b> An
 * operator opens the events client because something went wrong, and every question they can ask is
 * a query parameter on one route — deliberately, because a new literal under {@code /events} would
 * need a {@code quarkus.quinoa.ignored-path-prefixes} entry in the same commit, which is this
 * platform's standing trap. Parameters need none.
 *
 * <h2>The read doors are not one door, and that is the arm that only a launched artifact can show</h2>
 *
 * <ul>
 *   <li>{@code GET /events/api/events} takes {@code qits:admin} <b>or</b> {@code qits:system},
 *       because a durable consumer catching up from its watermark is a machine reading the log and
 *       must be able to.
 *   <li>{@code GET /events/api/events/names} and {@code GET /{id}} take {@code qits:admin}
 *       <b>alone</b>. They are the client's own affordances — a vocabulary to offer in a filter, and
 *       one row expanded — and a machine has no business with either: a consumer already knows the
 *       signatures it subscribes to, and it reads pages rather than rows.
 * </ul>
 *
 * <p>Under {@code @QuarkusTest} qits-auth-core's {@code %test} dev-user hands every request all four
 * platform roles, so that asymmetry is invisible to the whole unit suite. Here it is two arrows on
 * the diagram.
 *
 * <h2>Three filters, three different costs, and the story says which is which</h2>
 *
 * <p>{@code ?environment=} is an indexed equality on its own column. {@code ?q=} is a scan of the
 * opaque payload string, and this service parses no payload at all — that is what makes the
 * idempotent publish's byte-for-byte comparison true, and it is why there is no projected column to
 * be exact against. {@code ?attr=key=value} is the exact question {@code ?q=} cannot answer without
 * projecting anything: one {@code like} pattern per filter matching the literal {@code
 * "key":"value"}, <b>closing quote included</b>, leaning on the platform's canonical-JSON publisher
 * rather than on payload adjacency. This story pins that closing quote by asking for a value that is
 * a prefix of a real one and getting nothing.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
public class OperatorInvestigationIT {

  static final String CATEGORY = "operations";

  static final String CATEGORY_SLUG = Slugs.slug(CATEGORY);

  static final String STORY = "An operator traces one event's causes and consequences";

  static final String SLUG = Slugs.slug(STORY);

  /** This story's own vocabulary. */
  private static final String STARTED = "UserflowOpsPipelineStarted";

  private static final String ARTIFACT = "UserflowOpsArtifactPublished";

  private static final String DEPLOYED = "UserflowOpsDeploymentFinished";

  private static final String VOCABULARY = STARTED + "," + ARTIFACT + "," + DEPLOYED;

  private static final String STARTED_AT = "2026-08-23T10:00:00Z";

  private static final String ARTIFACT_AT = "2026-08-23T10:05:00Z";

  private static final String DEPLOYED_AT = "2026-08-23T10:10:00Z";

  /**
   * Canonical JSON — alphabetically sorted keys, string values quoted — because {@code ?attr=} leans
   * on exactly that guarantee rather than on where a key happens to sit in the string.
   */
  private static final String ROOT_PAYLOAD = "{\"repository\":\"qits-events\",\"run\":\"nightly\"}";

  private static final String DAEMON_PAYLOAD =
      "{\"packageType\":\"daemon\",\"repository\":\"qits-events\"}";

  private static final String SERVICE_PAYLOAD =
      "{\"packageType\":\"service\",\"repository\":\"qits-events\"}";

  private static final List<String> NEVER_IN_THE_BUNDLE = new ArrayList<>();

  @BeforeAll
  static void tapTheBus() {
    StoryNetwork.install();
  }

  @UserStory(value = STORY, category = CATEGORY)
  @UserStoryDescription(
      """
      A nightly pipeline ran and something about it is being asked after. Three events came out
      of it: the run itself, and two things it caused — an artifact published from the dev tier
      and a deployment finished on the platform tier, each carrying the run's id as its
      `parentId`.

      An operator opens the events client. First the vocabulary: which names does this log hold?
      That is the same list a subscriber may name in a subscribe frame, so a filter means one
      thing live and historically.

      Then the log itself, newest first, and then narrowed three different ways whose costs are
      not the same and whose differences the client's user has to understand. `?environment=` is
      an indexed equality on a column of its own. `?q=` is a substring of the payload, which this
      service stores as an opaque string and never parses — there is no single key meaning "which
      repository" to project anyway, since a build names it `repoId` and a release names it
      `repository`. And `?attr=key=value` is the exact question `?q=` cannot answer: it matches
      the literal `"key":"value"` with the CLOSING QUOTE included, so asking for `packageType=dae`
      finds no `daemon`.

      Then the chain, which is two directions and deliberately no endpoint of its own. Upwards is
      the `GET /{id}` that already existed, following `parentId`. Downwards is `?parentId=` on the
      list route, answered whole rather than paged, because a parent's children are bounded by a
      file in a repository rather than by history. A parent nothing names gives an empty list and
      not a 404 — this service does not know whether such an id is wrong, not here yet, or from a
      publisher it has never heard from, and "nothing was caused by it as far as I know" is the
      true answer in all three cases.

      And last, the doors, which are not one door. The list route admits a machine, because a
      durable consumer catching up IS a machine reading the log. The vocabulary and the
      single-row read do not: a consumer already knows the signatures it subscribes to, and it
      reads pages rather than rows. Under any @QuarkusTest here that asymmetry is invisible,
      because the %test dev-user hands every request all four platform roles.
      """)
  void anOperatorTracesOneEventsCausesAndConsequences(Interactions story, Network net) {
    net.declare(
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "select event by environment (indexed) and by payload scan, and by parent_id");

    // --- what the pipeline left behind ------------------------------------------------------------
    NetworkCapture.actor(StoryTarget.OUTBOX);
    String run = publish(STARTED, STARTED_AT, ROOT_PAYLOAD, null, "platform");
    String artifact = publish(ARTIFACT, ARTIFACT_AT, DAEMON_PAYLOAD, run, "dev");
    String deployment = publish(DEPLOYED, DEPLOYED_AT, SERVICE_PAYLOAD, run, "platform");
    story
        .note(
            "a nightly pipeline leaves three events behind: the run, and two things it caused, each"
                + " carrying the run's id as its parentId — the edge a chain is drawn from, and the"
                + " reason parentId sits INSIDE the idempotent publish's comparison")
        .as("a-run-and-the-two-events-it-caused");

    // --- the vocabulary ---------------------------------------------------------------------------
    NetworkCapture.actor(StoryTarget.PERSON_SESSION);
    StoryTarget.operator()
        .when()
        .get(StoryTarget.NAMES)
        .then()
        .statusCode(200)
        .body("names", hasItems(STARTED, ARTIFACT, DEPLOYED));
    story
        .note(
            "the operator asks what names this log holds — the same vocabulary a subscriber may put"
                + " in a subscribe frame, which is what makes a filter mean one thing live and one"
                + " thing historically. It is a literal beside the /{id} template, and JAX-RS sorts"
                + " a literal ahead of a template, which is a spec guarantee this service leans on")
        .as("the-log-offers-its-own-vocabulary");

    // --- the log, newest first ---------------------------------------------------------------------
    StoryTarget.operator()
        .queryParam("name", VOCABULARY)
        .when()
        .get(StoryTarget.EVENTS)
        .then()
        .statusCode(200)
        .body("events", hasSize(3))
        .body("events[0].name", equalTo(DEPLOYED))
        .body("events[2].name", equalTo(STARTED));
    story
        .note(
            "and reads them newest first, which is what a person wants and the opposite of what a"
                + " catch-up consumer wants. The sort is (occurredAt desc, id desc) and the id half"
                + " is not decoration: occurredAt ties by construction, and dropping the tiebreaker"
                + " would make two identical requests disagree about a tied pair")
        .as("newest-first-for-a-person");

    // --- three narrowings, three different costs ----------------------------------------------------
    StoryTarget.operator()
        .queryParam("name", VOCABULARY)
        .queryParam("environment", "dev")
        .when()
        .get(StoryTarget.EVENTS)
        .then()
        .statusCode(200)
        .body("events", hasSize(1))
        .body("events[0].name", equalTo(ARTIFACT));
    story
        .note(
            "narrowed by TIER, which is envelope data on its own indexed column rather than a"
                + " payload scan — the tier the publisher stamped from its own QITS_ENVIRONMENT."
                + " It came back as data when the platform stopped running a broker per"
                + " environment: which instance you dialled used to be the whole scoping")
        .as("by-tier-an-indexed-equality");

    StoryTarget.operator()
        .queryParam("name", VOCABULARY)
        .queryParam("q", "nightly")
        .when()
        .get(StoryTarget.EVENTS)
        .then()
        .statusCode(200)
        .body("events", hasSize(1))
        .body("events[0].name", equalTo(STARTED));
    story
        .note(
            "narrowed by a substring of the payload, which this service stores opaquely and parses"
                + " NOWHERE — that is what makes the idempotent publish's byte-for-byte comparison"
                + " true. It over-matches slightly and says so, which is the honest shape of the"
                + " question: there is no one key meaning 'which repository' to project anyway")
        .as("by-payload-substring-a-scan");

    StoryTarget.operator()
        .queryParam("name", VOCABULARY)
        .queryParam("attr", "packageType=daemon")
        .when()
        .get(StoryTarget.EVENTS)
        .then()
        .statusCode(200)
        .body("events", hasSize(1))
        .body("events[0].name", equalTo(ARTIFACT));
    StoryTarget.operator()
        .queryParam("name", VOCABULARY)
        .queryParam("attr", "packageType=dae")
        .when()
        .get(StoryTarget.EVENTS)
        .then()
        .statusCode(200)
        .body("events", hasSize(0));
    story
        .note(
            "and narrowed to ONE key, exactly, without projecting anything: the pattern matches the"
                + " literal \"key\":\"value\" with the CLOSING QUOTE in it, so a request for"
                + " packageType=dae finds no daemon. It leans on the canonical-JSON publisher's"
                + " guarantee — sorted keys, quoted string values — rather than on where a field"
                + " happens to sit, so it stays exact as fields are added around it")
        .as("by-one-attribute-exactly-closing-quote-included");

    // --- the chain, both directions -----------------------------------------------------------------
    StoryTarget.operator()
        .when()
        .get(StoryTarget.event(artifact))
        .then()
        .statusCode(200)
        .body("event.parentId", equalTo(run))
        .body("event.environment", equalTo("dev"));
    story
        .note(
            "upwards is the GET /{id} that already existed, following parentId — no chain, depth or"
                + " root endpoint was added, and a client walking it must bound its own depth and"
                + " remember the ids it has seen, because nothing here prevents a cycle")
        .as("upwards-by-following-parent-id");

    StoryTarget.operator()
        .queryParam("parentId", run)
        .when()
        .get(StoryTarget.EVENTS)
        .then()
        .statusCode(200)
        .body("events", hasSize(2))
        .body("events.id", hasItems(artifact, deployment))
        .body("nextCursor", nullValue());
    story
        .note(
            "downwards is ?parentId= on the list route, answered WHOLE and taking none of the"
                + " filters above: a parent's children are one per artifact a pipeline declares —"
                + " bounded by a file in a repository rather than by history — so paging them would"
                + " be a parameter that never fires")
        .as("downwards-answered-whole");

    StoryTarget.operator()
        .queryParam("parentId", mint())
        .when()
        .get(StoryTarget.EVENTS)
        .then()
        .statusCode(200)
        .body("events", hasSize(0));
    story
        .note(
            "and a parent nothing names is an empty list, never a 404. This service cannot tell"
                + " whether such an id is wrong, not here yet, or from a publisher it has never"
                + " heard from — and 'nothing was caused by it as far as I know' is the true answer"
                + " in all three cases")
        .as("an-unknown-parent-is-an-empty-list");

    // --- the doors, which are not one door ------------------------------------------------------------
    NetworkCapture.actor(StoryTarget.DURABLE_CONSUMER);
    StoryTarget.consumer().when().get(StoryTarget.NAMES).then().statusCode(403);
    StoryTarget.consumer().when().get(StoryTarget.event(run)).then().statusCode(403);
    story
        .note(
            "and the read doors are not one door. The list route admits a machine — a durable"
                + " consumer catching up from its watermark IS a machine reading this log, and it"
                + " must be able to. The vocabulary and the single-row read do not: a consumer"
                + " already knows the signatures it subscribes to, and it reads pages rather than"
                + " rows. Invisible to every @QuarkusTest here, where the %test dev-user hands"
                + " every request all four platform roles")
        .as("a-machine-reads-pages-not-rows");
  }

  /** One create, under an id the publisher chose. Returned so the story can compare, never print. */
  private static String publish(
      String name, String occurredAt, String payload, String parentId, String environment) {
    String id = mint();
    StoryTarget.publisher()
        .body(StoryTarget.envelope(name, occurredAt, payload, parentId, environment))
        .when()
        .put(StoryTarget.event(id))
        .then()
        .statusCode(201);
    return id;
  }

  /** A UUID this story mints and never prints. */
  private static String mint() {
    String id = UUID.randomUUID().toString();
    NEVER_IN_THE_BUNDLE.add(id);
    return id;
  }

  @AfterAll
  static void theOperationsStoryIsComplete() {
    ReportAssertions.assertComplete(CATEGORY_SLUG, SLUG, UserflowReport.PASSED);

    // --- the graph: eleven requests, seven arrows -------------------------------------------------
    // SIX of those requests are the person's reads of the list route — the whole log, three
    // narrowings, the children, and a parent nothing names — and they are ONE arrow, because they
    // differ only in a query string and a query string never reaches a label. On this service that
    // is the rule with the widest reach: the entire read model is query parameters.
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
        StoryTarget.PERSON_SESSION,
        StoryTarget.SERVICE,
        StoryTarget.read(StoryTarget.NAMES, 200));
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        SLUG,
        NetworkEdge.HTTP,
        StoryTarget.PERSON_SESSION,
        StoryTarget.SERVICE,
        StoryTarget.read(StoryTarget.EVENTS, 200));
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        SLUG,
        NetworkEdge.HTTP,
        StoryTarget.PERSON_SESSION,
        StoryTarget.SERVICE,
        StoryTarget.read(StoryTarget.ANY_EVENT, 200));
    // The asymmetry, as two arrows a unit suite cannot draw.
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        SLUG,
        NetworkEdge.HTTP,
        StoryTarget.DURABLE_CONSUMER,
        StoryTarget.SERVICE,
        StoryTarget.read(StoryTarget.NAMES, 403));
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        SLUG,
        NetworkEdge.HTTP,
        StoryTarget.DURABLE_CONSUMER,
        StoryTarget.SERVICE,
        StoryTarget.read(StoryTarget.ANY_EVENT, 403));
    ReportAssertions.assertDeclaredEdge(
        CATEGORY_SLUG,
        SLUG,
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "select event by environment (indexed) and by payload scan, and by parent_id");

    // EXACTLY seven — and no `socket` or `event` arrow anywhere, because an operator investigating
    // an incident holds no connection to this bus and is pushed nothing. The client polls.
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, SLUG, 7);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY_SLUG,
        SLUG,
        List.of(
            StoryTarget.OUTBOX,
            StoryTarget.PERSON_SESSION,
            StoryTarget.DURABLE_CONSUMER,
            StoryTarget.SERVICE));

    for (String step :
        List.of(
            "a-run-and-the-two-events-it-caused",
            "the-log-offers-its-own-vocabulary",
            "newest-first-for-a-person",
            "by-tier-an-indexed-equality",
            "by-payload-substring-a-scan",
            "by-one-attribute-exactly-closing-quote-included",
            "upwards-by-following-parent-id",
            "downwards-answered-whole",
            "an-unknown-parent-is-an-empty-list",
            "a-machine-reads-pages-not-rows")) {
      ReportAssertions.assertStepId(CATEGORY_SLUG, SLUG, step);
    }

    for (String id : NEVER_IN_THE_BUNDLE) {
      ReportAssertions.assertNotLeaked(CATEGORY_SLUG, SLUG, id);
    }
  }
}
