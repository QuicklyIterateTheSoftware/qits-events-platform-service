package eu.wohlben.qits.events.stream;

import io.vertx.core.Vertx;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A subscriber that never leaves this JVM: a real Vert.x WebSocket client dialling the real
 * endpoint and framing the real protocol — the same fixture shape qits-ci's {@code FakeCiDaemon}
 * carries, and for the same reason. The server cannot tell it from qits-ci's eventsourcing module,
 * so subscription, matching and fan-out are all provable in a suite with nothing on PATH.
 *
 * <p>Deliberately dumb: it holds no state and answers nothing on its own. Each test sends the frames
 * it wants, including the wrong ones, which is how a malformed subscribe is testable at all.
 */
public final class FakeSubscriber implements AutoCloseable {

  private final Vertx vertx;
  private final WebSocketClient client;
  private final WebSocket socket;
  private final BlockingQueue<String> received = new ArrayBlockingQueue<>(256);

  /** Dial with no headers at all — the anonymous client, which is a caller in its own right. */
  public static FakeSubscriber dial(URI endpoint) throws Exception {
    return dial(endpoint, Map.of());
  }

  /**
   * The same dial, carrying headers on the UPGRADE — which is where this socket's door is: the
   * endpoint's class-level {@code @RolesAllowed} is checked on the handshake, so a connection is
   * either authorised before it exists or refused outright. Pass the pair qits-gateway asserts
   * ({@code X-Qits-User} / {@code X-Qits-Roles}) and the server cannot tell this from a sibling
   * service's eventstream jar, which dials with exactly those two headers.
   *
   * <p>An empty map is the unauthenticated dial, and against a launched artifact that is a
   * <em>refusal</em>: this method then throws, which is the assertable form of the door being shut.
   */
  public static FakeSubscriber dial(URI endpoint, Map<String, String> headers) throws Exception {
    Vertx vertx = Vertx.vertx();
    try {
      WebSocketClient client = vertx.createWebSocketClient();
      WebSocketConnectOptions options =
          new WebSocketConnectOptions()
              .setHost(endpoint.getHost())
              .setPort(endpoint.getPort())
              .setURI(endpoint.getPath());
      for (Map.Entry<String, String> header : headers.entrySet()) {
        options.addHeader(header.getKey(), header.getValue());
      }
      WebSocket socket =
          client
              .connect(options)
              .toCompletionStage()
              .toCompletableFuture()
              .get(20, TimeUnit.SECONDS);
      return new FakeSubscriber(vertx, client, socket);
    } catch (Exception failedToUpgrade) {
      vertx.close();
      throw failedToUpgrade;
    }
  }

  private FakeSubscriber(Vertx vertx, WebSocketClient client, WebSocket socket) {
    this.vertx = vertx;
    this.client = client;
    this.socket = socket;
    socket.textMessageHandler(received::offer);
  }

  /** Replace this connection's subscription set. {@code "*"} means everything. */
  public void subscribe(String... signatures) throws Exception {
    StringBuilder frame = new StringBuilder("{\"subscribe\":[");
    for (int i = 0; i < signatures.length; i++) {
      frame.append(i == 0 ? "" : ",").append('"').append(signatures[i]).append('"');
    }
    sendRaw(frame.append("]}").toString());
  }

  /** Send text the server may or may not be able to read — the one thing a good client never does. */
  public void sendRaw(String text) throws Exception {
    socket.writeTextMessage(text).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  /** The next frame the server pushed, or null if none arrived in time. */
  public String next(Duration timeout) throws InterruptedException {
    return received.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
  }

  public boolean isOpen() {
    return !socket.isClosed();
  }

  @Override
  public void close() {
    try {
      socket.close();
    } catch (RuntimeException alreadyGone) {
      // nothing to do
    }
    client.close();
    vertx.close();
  }
}
