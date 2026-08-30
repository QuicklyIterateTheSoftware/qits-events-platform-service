package eu.wohlben.qits.events.stories.support;

import eu.wohlben.qits.events.testdb.EmbeddedPg;
import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <b>One launched qits-events for the whole story catalogue</b>, and every seam a story moves,
 * declared once.
 *
 * <p>A {@code @TestProfile} is what failsafe launches a process for, so two profiles would be two
 * buses — two boots, two databases, and two in-memory subscription registries, which for <em>this</em>
 * service is the sharpest form of the problem: the fan-out table lives in the process, so a
 * subscriber connected to one launch is invisible to a publish that reached the other. Every story
 * class names this one, {@code EventBusBootstrapIT} included; it is a story class like the others
 * and it happens to be the oldest.
 *
 * <h2>Why the PACKAGED artifact, and not a {@code @QuarkusTest}</h2>
 *
 * <p>Three of the things these stories are about exist only in a {@code NORMAL} launch, and no
 * {@code @QuarkusTest} in this repository can reach any of them:
 *
 * <ul>
 *   <li><b>The doors are real.</b> Publishing takes {@code qits:system}, {@code /events/api/events}
 *       takes either role, {@code /names} and {@code GET /{id}} and {@code DELETE} take {@code
 *       qits:admin} alone, and the socket takes either. Under {@code @QuarkusTest} qits-auth-core's
 *       {@code %test} dev-user hands every request all four platform roles before an annotation is
 *       consulted, and {@code ForwardAuthMechanism} is {@code LaunchMode.NORMAL}-guarded on top of
 *       that — so a suite cannot tell one door from another.
 *   <li><b>The socket survived augmentation.</b> websockets-next registers {@code /events/stream} at
 *       AUGMENTATION and its class-level {@code @RolesAllowed} secures the HTTP <em>upgrade</em>, so
 *       both "the endpoint is there" and "the handshake is the door" are claims only a launched
 *       artifact settles.
 *   <li><b>The log is the log.</b> Every catch-up read below runs through Flyway's real migration
 *       resources against the provisioned postgres — the {@code ${QITS_RESOURCE_DB_URL}} triple the
 *       events jar ships as an expression — and the composite cursor's index with it.
 * </ul>
 *
 * <p><b>Every key here is a RUNTIME key.</b> A packaged process takes its configuration as {@code
 * -D} arguments on an artifact that was already built, so a build-time key would be silently ignored
 * and these stories would prove something other than what they say. Everything that makes this
 * service what it is — {@code quarkus.rest.path}, the non-application root, the Quinoa ignore list,
 * the four OTel logging keys — is left exactly as it ships.
 *
 * <h2>What a deployment supplies, and it is nearly all of what is here</h2>
 *
 * <p>{@code .config/qits/deployments.yml} declares {@code resources: postgresql:db} and nothing
 * else, so a qits-events deployment is a process, a database and an address to export telemetry to.
 * The database arrives as the platform's <b>generic</b> resource triple rather than as datasource
 * keys, exactly as {@code PackagedSurfaceIT.PackagedUnderTarget} does it: the events jar ships
 * {@code jdbc.url=${QITS_RESOURCE_DB_URL}} and its two siblings, so supplying the variables leaves
 * the <b>shipped expression itself</b> under test.
 *
 * <p>It is a database of this catalogue's own on this JVM's embedded postgres — {@code
 * events_userflows_it}, beside {@code PackagedSurfaceIT}'s and {@code PackagedLogBridgeIT}'s — so
 * the three launched processes cannot write into each other's schema. It is also <b>fresh</b>, which
 * is why every event name below can be a readable literal instead of a nonce; what it is <em>not</em>
 * is cleaned between stories, because {@code flyway.clean-at-start} lives in the {@code @QuarkusTest}
 * suite's test resources and not in the jar. The catalogue is order-independent by construction
 * instead: <b>every story owns its own event names and every read filters on {@code ?name=}</b>, so
 * one story's rows can never satisfy or spoil another's assertion. Nothing here counts the whole log.
 *
 * <p><b>The url travels through a system property rather than a static field</b> — the trick both
 * packaged-artifact ITs beside this one carry: a test profile is instantiated in more than one
 * classloader, so a field written by one copy is not the field the other reads, while the process has
 * exactly one property table.
 *
 * <h2>One thing is OFF, and it is the only thing this process would otherwise dial</h2>
 *
 * <p><b>The OTLP exporter.</b> The shipped configuration points this service's SDK at {@code
 * http://qits-observability:8080}, a name that resolves on {@code qits-net} and nowhere else, so a
 * launched artifact would spend the run retrying an export into the void and bury the story's own log
 * under the failures. An exporter also flushes on a schedule of its own, on its own thread, so its
 * batches would draw arrows into whichever story happened to be open — a {@code networkHash} that
 * never settles.
 *
 * <p>So <b>no story in this catalogue covers this service's self-export</b>, and none claims its
 * absence either: an {@code assertNoEdgesTo} over an exporter this profile switched off would be a
 * claim about the profile rather than about the service. There is nothing else to darken — this
 * service is the bus, so it carries no qits-eventstream jar and publishes to nobody, itself included.
 */
public class StoryProfile implements QuarkusTestProfile {

  /** Where the url is parked for whichever copy of this class is asked second. */
  private static final String URL_PROPERTY = "qits.test.userflows-it.db-url";

  /** This catalogue's own database on the one embedded postgres. */
  private static final String DATABASE = "events_userflows_it";

  @Override
  public Map<String, String> getConfigOverrides() {
    // LinkedHashMap rather than Map.of: the order is the order this file explains them in, and a
    // reader diffing a launch command should find them in it.
    Map<String, String> overrides = new LinkedHashMap<>();

    // The platform's generic resource contract, exactly as a deployment fills it.
    overrides.put("QITS_RESOURCE_DB_URL", databaseUrl());
    overrides.put("QITS_RESOURCE_DB_USERNAME", EmbeddedPg.USER);
    overrides.put("QITS_RESOURCE_DB_PASSWORD", EmbeddedPg.PASSWORD);

    // Dark outside a deployment, like %dev and %test — and the only dial-out this process has
    // besides its datasource. See the class javadoc for why it is a neutralisation, not tidiness.
    overrides.put("quarkus.otel.sdk.disabled", "true");

    return Map.copyOf(overrides);
  }

  private static synchronized String databaseUrl() {
    String recorded = System.getProperty(URL_PROPERTY);
    if (recorded != null) {
      return recorded;
    }
    // localhost resolves for the launched process too — it is a child of this JVM on this host.
    String url = EmbeddedPg.url(DATABASE);
    System.setProperty(URL_PROPERTY, url);
    return url;
  }
}
