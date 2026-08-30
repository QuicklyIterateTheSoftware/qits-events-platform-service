package eu.wohlben.qits.events.stories.support;

import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkTaps;

/**
 * <b>The whole capture wiring of this catalogue, in one call</b> — so a story class's {@code
 * @BeforeAll} is one line and no class can wire half of it.
 *
 * <p>There are <b>two feeds and no {@code NetworkCapture.source}</b>, and that is this service's
 * shape rather than an omission:
 *
 * <ul>
 *   <li>{@link NetworkTaps#restAssured} is the shipped incoming tap — every request a story sends
 *       becomes {@code <actor> -> qits-events}, labelled with the status this service answered.
 *   <li>{@code stream/FakeSubscriber} reports the socket half from inside itself, because a
 *       RestAssured filter cannot see a websocket at all. It is installed by <em>being used</em>: a
 *       story that dials one gets the dial, the refused upgrade and every pushed frame; a story that
 *       does not is a story with no socket in it.
 * </ul>
 *
 * <p>A {@code source} is for a <em>cumulative</em> recording — a mock's request log — read lazily at
 * story end and attributed by a cursor. There is nothing to record here: qits-events has no upstream
 * to stand a mock in front of, so every edge in this catalogue arrives through {@link
 * NetworkCapture#observe}, appended at the moment the call returns. Two simplifications fall out of
 * that, and both are worth naming because the sibling catalogues cannot have them:
 *
 * <ul>
 *   <li><b>Story order is not load-bearing.</b> A cumulative recording attributes pre-story traffic
 *       to whichever story drains first — which is why a sibling's boot-owning class pins its method
 *       order and its edge count. {@code observe} has no such window: nothing the launched process
 *       did before the first story can land in any diagram, because nothing was watching it.
 *   <li><b>No story has to await a far side.</b> There is no async forward racing the story-end
 *       drain, so no relative-count poll and no boot floor anywhere in this catalogue.
 * </ul>
 *
 * <p><b>The one asynchrony that IS here is the pushed frame</b>, and it is handled where it arises
 * rather than here: a frame lands on a Vert.x event-loop thread at a moment the story does not
 * control, so {@code FakeSubscriber} reads the actor once at the dial and keeps it, and stops
 * observing the moment the story closes the connection. Read that class's comment before touching
 * either tap.
 *
 * <p>Both calls below are idempotent: {@link NetworkTaps#restAssured(String)} installs at most one
 * filter per service name (RestAssured's filter list <i>appends</i>), and the normalizer is a single
 * JVM slot every class sets to the same function. So every story class may call {@link #install()}
 * from its own {@code @BeforeAll} without the diagram doubling an edge.
 */
public final class StoryNetwork {

  private StoryNetwork() {}

  /**
   * Install the incoming tap and claim the label-normalizer slot.
   *
   * <p>The normalizer is the identity here, deliberately and not vacuously — see {@link
   * StoryTarget}'s javadoc: every run-local value on this service's surface is a UUID, which the
   * default scrubber already rewrites, and every other distinction lives in a query string the tap
   * never labels. Claiming the single slot from one place is what makes that a decision, and {@code
   * StoryTarget.served} routes an assertion's expected label through the very same function, so the
   * two sides move together if it is ever given a job.
   */
  public static void install() {
    NetworkTaps.restAssured(StoryTarget.SERVICE);
    NetworkCapture.labelNormalizer(StoryTarget.NORMALIZER);
  }
}
