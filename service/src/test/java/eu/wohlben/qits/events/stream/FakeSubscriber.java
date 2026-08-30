package eu.wohlben.qits.events.stream;

import eu.wohlben.qits.userflows.Labels;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
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
 *
 * <p><b>It is also the userflow tap for this socket.</b> A RestAssured filter sees every HTTP
 * request a story makes, but nothing sees a websocket — so the dial and every pushed frame are
 * reported to {@link NetworkCapture} from here, which is the only place that can see them. Three
 * decisions are baked in and each is the framework's rule rather than this fixture's taste:
 *
 * <ul>
 *   <li><b>Direction is who initiated.</b> The dial is {@code consumer -> qits-events} and is
 *       {@code socket} kind; a pushed frame is {@code qits-events -> consumer} and is {@code event}
 *       kind, because the server is what initiates a push. Data flows both ways on one connection
 *       and the diagram draws the two separately for exactly that reason.
 *   <li><b>The consumer's name is fixed at the dial.</b> {@link NetworkCapture#actor()} is read once,
 *       when the connection is made, and kept — a frame arrives on a Vert.x event-loop thread at a
 *       moment the story does not control, and reading the sticky actor there would name whoever the
 *       story happened to be acting as by then.
 *   <li><b>A refused upgrade is an edge, and a deterministic one.</b> The client sees the dial fail
 *       before {@link #dial} returns, so "the handshake was refused" is observed rather than
 *       claimed. What the client cannot see is <em>why</em> — a rejected upgrade and an unreachable
 *       port fail the same way from here — so the label says {@code -> refused} and the story's own
 *       assertions say which.
 *   <li><b>Observation ends at {@link #close}, not at the socket's own end.</b> A frame already on
 *       the wire when a story lets go of its connection would otherwise be observed on an
 *       event-loop thread after the story border and drawn into the <em>next</em> story's diagram —
 *       an arrow nothing in that story caused. So closing stops the tap first and waits for the
 *       close to complete, which is what makes a later story's "nothing was pushed" a claim about
 *       that story.
 * </ul>
 *
 * <p>Nothing about this costs the ordinary {@code @QuarkusTest}s that use this class anything: the
 * capture registry is a JVM-global list that only a running {@code @UserStory} ever drains, so
 * outside a story the observations are recorded and never read.
 */
public final class FakeSubscriber implements AutoCloseable {

  /** How the diagram names the server end of this socket. */
  private static final String SERVICE = "qits-events";

  /**
   * The label of every pushed frame. It is a constant and not read off the frame because the wire
   * carries no discriminator to read: this socket pushes exactly one shape, {@code
   * eu.wohlben.qits.events.dto.EventCreated}'s own JSON, flat and untagged. Naming it here keeps the
   * label template-shaped — one arrow however many frames arrive, which is the diagram's job, while
   * counting them is the story's.
   */
  private static final String FRAME = "EventCreated frame";

  private final Vertx vertx;
  private final WebSocketClient client;
  private final WebSocket socket;

  /** The narrative initiator, read at the dial and kept — see the class comment. */
  private final String consumer;

  private final BlockingQueue<String> received = new ArrayBlockingQueue<>(256);

  /**
   * Whether pushed frames still belong to a story. Cleared by {@link #close} <em>before</em> the
   * socket goes down — see the class comment. Volatile because it is written on the story's thread
   * and read on a Vert.x event-loop one.
   */
  private volatile boolean observing = true;

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
      String dialled = "WS " + Labels.scrub(endpoint.getPath()) + " subscribe";
      String consumer = NetworkCapture.actor();
      WebSocket socket;
      try {
        socket =
            client
                .connect(options)
                .toCompletionStage()
                .toCompletableFuture()
                .get(20, TimeUnit.SECONDS);
      } catch (Exception refused) {
        // Observed, not claimed: the client saw this happen, here, before dial() returned.
        NetworkCapture.observe(NetworkEdge.SOCKET, consumer, SERVICE, dialled + " -> refused");
        throw refused;
      }
      NetworkCapture.observe(NetworkEdge.SOCKET, consumer, SERVICE, dialled);
      return new FakeSubscriber(vertx, client, socket, consumer);
    } catch (Exception failedToUpgrade) {
      vertx.close();
      throw failedToUpgrade;
    }
  }

  private FakeSubscriber(Vertx vertx, WebSocketClient client, WebSocket socket, String consumer) {
    this.vertx = vertx;
    this.client = client;
    this.socket = socket;
    this.consumer = consumer;
    socket.textMessageHandler(
        frame -> {
          // The push, and its direction is the server's: qits-events decided to send this. Only
          // while the story still holds the connection — a frame that lands after close() belongs
          // to no story and must not be drawn into whichever one is open by then.
          if (observing) {
            NetworkCapture.observe(NetworkEdge.EVENT, SERVICE, this.consumer, FRAME);
          }
          received.offer(frame);
        });
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
    // The tap goes down FIRST and the close is waited for, so that no frame can be observed after
    // the story that dialled this connection has ended. See the class comment.
    observing = false;
    try {
      socket.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } catch (Exception alreadyGone) {
      // nothing to do: an already-dead socket is closed
    }
    client.close();
    vertx.close();
  }
}
