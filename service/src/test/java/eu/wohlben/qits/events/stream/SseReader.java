package eu.wohlben.qits.events.stream;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A browser's {@code EventSource}, as far as this server can tell: a plain credentialed {@code GET}
 * that reads {@code text/event-stream} off the wire and parses it by hand.
 *
 * <p><b>Deliberately raw.</b> It does not use a JAX-RS {@code SseEventSource} — this repository has
 * no REST client on its classpath and must not grow one for a test — and, more to the point, a typed
 * client would hide the very thing worth pinning. The SSE framing <em>is</em> the contract here: an
 * {@code id:} field carrying the event id, a {@code data:} field carrying the envelope's own JSON
 * bytes and nothing wrapped around them, and comment lines that no listener ever sees. Parsing the
 * lines is what makes those assertable.
 *
 * <p>{@link java.net.http.HttpClient} with {@code BodyHandlers.ofLines()} returns as soon as the
 * response <em>head</em> is written, which is why the server flushes an opening comment: without it
 * {@link #open} would block on a stream that is behaving perfectly. A daemon thread drains the line
 * stream into a queue, so the test thread can assert on arrival <em>and</em> on silence.
 *
 * <p>{@link #close} calls {@code shutdownNow}, which drops the connection — the server sees the
 * close, cancels the {@code Multi}, and the sink leaves the subscription table. A test can assert
 * that departure through {@code EventStreamSubscriptions.subscriberCountFor}.
 */
public final class SseReader implements AutoCloseable {

  /** One parsed SSE event: the fields this stream uses, and no others. */
  public record Frame(String id, String data) {}

  private final HttpClient client;
  private final HttpResponse<?> response;
  private final Thread pump;

  /** Raw lines, in arrival order, exactly as they came off the wire (blank lines included). */
  private final BlockingQueue<String> lines = new ArrayBlockingQueue<>(1024);

  private SseReader(HttpClient client, HttpResponse<?> response, Thread pump) {
    this.client = client;
    this.response = response;
    this.pump = pump;
  }

  /** Open a stream with no headers at all — an anonymous reader, which is a caller in its own right. */
  public static SseReader open(URI endpoint) throws Exception {
    return open(endpoint, Map.of());
  }

  /**
   * Open a stream carrying the pair qits-gateway asserts ({@code X-Qits-User} / {@code
   * X-Qits-Roles}), which is what a browser session becomes by the time it reaches this service.
   *
   * <p>Returns as soon as the response head is readable, whatever the status: a refusal is a
   * response like any other, and {@link #status()} is how a test tells the door from the stream.
   */
  public static SseReader open(URI endpoint, Map<String, String> headers) throws Exception {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    HttpRequest.Builder request =
        HttpRequest.newBuilder(endpoint).header("Accept", "text/event-stream").GET();
    headers.forEach(request::header);

    HttpResponse<java.util.stream.Stream<String>> response;
    try {
      response = client.send(request.build(), HttpResponse.BodyHandlers.ofLines());
    } catch (Exception refused) {
      client.shutdownNow();
      throw refused;
    }

    SseReader[] holder = new SseReader[1];
    Thread pump =
        Thread.ofPlatform()
            .daemon()
            .name("sse-reader")
            .unstarted(
                () -> {
                  try {
                    response.body().forEach(line -> holder[0].lines.offer(line));
                  } catch (RuntimeException streamEnded) {
                    // The connection went away; there is nothing left to read and nobody to tell.
                  }
                });
    SseReader reader = new SseReader(client, response, pump);
    holder[0] = reader;
    pump.start();
    return reader;
  }

  public int status() {
    return response.statusCode();
  }

  public String contentType() {
    return response.headers().firstValue("content-type").orElse("");
  }

  /** The next raw line, blank lines included, or null if none arrived in time. */
  public String nextLine(Duration timeout) throws InterruptedException {
    return lines.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
  }

  /**
   * The next <em>event</em>: fields accumulated until the blank line that ends a block, skipping
   * blocks that carry no {@code data:} at all.
   *
   * <p>Skipping those is what makes "no event arrived" assertable on a stream that is also sending
   * comments — the opening one and the keepalives are, by the protocol's own definition, not events.
   *
   * @return the frame, or null if none was completed within {@code timeout}
   */
  public Frame nextFrame(Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    String id = null;
    StringBuilder data = null;
    while (true) {
      long left = deadline - System.nanoTime();
      if (left <= 0) {
        return null;
      }
      String line = lines.poll(left, TimeUnit.NANOSECONDS);
      if (line == null) {
        return null;
      }
      if (line.isEmpty()) {
        if (data != null) {
          return new Frame(id, data.toString());
        }
        id = null;
        continue;
      }
      if (line.startsWith(":")) {
        continue; // a comment: the open marker or a keepalive, and never an event
      }
      if (line.startsWith("id:")) {
        id = line.substring("id:".length()).trim();
      } else if (line.startsWith("data:")) {
        String chunk = line.substring("data:".length());
        // The spec strips ONE leading space, and RESTEasy writes one.
        if (chunk.startsWith(" ")) {
          chunk = chunk.substring(1);
        }
        if (data == null) {
          data = new StringBuilder(chunk);
        } else {
          data.append('\n').append(chunk);
        }
      }
    }
  }

  @Override
  public void close() {
    client.shutdownNow();
    pump.interrupt();
  }
}
