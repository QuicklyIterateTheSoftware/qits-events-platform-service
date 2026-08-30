package eu.wohlben.qits.events.stories.support;

import static org.junit.jupiter.api.Assertions.assertNull;

import eu.wohlben.qits.events.stream.FakeSubscriber;
import java.time.Duration;
import java.util.UUID;

/**
 * The four things every story that holds a socket has to do, in one place — because all four exist
 * for the same reason and getting any of them wrong is a flake rather than a failure.
 *
 * <p><b>This protocol acknowledges nothing.</b> {@code {"subscribe": [...]}} goes out and the server
 * answers with silence, so a client cannot learn that its subscription took except by
 * <em>receiving something</em>. A publish that raced the subscribe frame would be delivered to
 * nobody and would look exactly like a broken fan-out. {@link #awaitSubscriptionIsLive} is the only
 * honest way round it: publish under a signature the connection named until a frame comes back.
 *
 * <p>Each attempt is its own UUID and therefore its own CREATE, because <b>only a create
 * broadcasts</b> — re-PUTting one id is a replay after the first attempt and would push nothing
 * however long the loop waited.
 *
 * <p><b>And silence is only evidence once the queue is empty.</b> A story that asserts "nothing was
 * pushed" after a warm-up whose frames are still in flight is asserting about its own leftovers, so
 * {@link #drain} runs first, every time.
 */
public final class StoryStream {

  private StoryStream() {}

  /** How long a frame has to arrive before the fan-out is declared broken. */
  public static final Duration ARRIVAL = Duration.ofSeconds(20);

  /** How long a silence is watched before it is believed. */
  public static final Duration SILENCE = Duration.ofSeconds(2);

  /**
   * Publish events named {@code signature} until one comes back on {@code consumer} — the only proof
   * of subscription this protocol offers. The caller must have named the publishing actor already:
   * these are real publishes and they draw the publisher's own arrow.
   */
  public static void awaitSubscriptionIsLive(
      FakeSubscriber consumer, String signature, String occurredAt, String payload)
      throws Exception {
    String envelope = StoryTarget.envelope(signature, occurredAt, payload);
    long deadline = System.nanoTime() + ARRIVAL.toNanos();
    while (System.nanoTime() < deadline) {
      StoryTarget.publisher()
          .body(envelope)
          .when()
          .put(StoryTarget.event(UUID.randomUUID().toString()))
          .then()
          .statusCode(201);
      if (consumer.next(Duration.ofSeconds(2)) != null) {
        return;
      }
    }
    throw new AssertionError(
        "the packaged artifact never pushed a frame for "
            + signature
            + " — the subscription never took, or the fan-out is not running");
  }

  /** One poll: whatever frame is next, or {@code null} if the fan-out did not reach this client. */
  public static String awaitFrameOnce(FakeSubscriber consumer) throws Exception {
    return consumer.next(SILENCE);
  }

  /** The next frame naming {@code signature}, skipping whatever else is still in flight. */
  public static String awaitFrame(FakeSubscriber consumer, String signature) throws Exception {
    long deadline = System.nanoTime() + ARRIVAL.toNanos();
    while (System.nanoTime() < deadline) {
      String frame = consumer.next(Duration.ofSeconds(2));
      if (frame != null && frame.contains("\"name\":\"" + signature + "\"")) {
        return frame;
      }
    }
    return null;
  }

  /** Empty the queue, so a later silence is the server's and not a leftover's. */
  public static void drain(FakeSubscriber consumer) throws Exception {
    while (consumer.next(Duration.ofMillis(500)) != null) {
      // nothing to do: a warm-up's frames are not the story's business
    }
  }

  /**
   * Assert nothing arrived, and say what the silence means. The wait is a real one — a fan-out that
   * had happened would have landed in microseconds, so two seconds is the difference between "not
   * yet" and "not at all".
   */
  public static void assertSilent(FakeSubscriber consumer, String because) throws Exception {
    assertNull(consumer.next(SILENCE), because);
  }
}
