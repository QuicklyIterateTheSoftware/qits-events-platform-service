package eu.wohlben.qits.events.stream;

import io.quarkus.websockets.next.WebSocketConnection;
import org.jboss.logging.Logger;

/**
 * The {@link EventStreamSink} over a websockets-next connection — the transport {@link
 * EventStreamSocket} owns, and the one every sibling service's eventstream jar dials.
 *
 * <p>It carries the send discipline that used to live inside {@code EventStreamSubscriptions.push}
 * and is unchanged by the move: {@code sendText(…)} is a {@code Uni}, subscribed to with a failure
 * handler, and never {@code sendTextAndAwait} — which is {@code sendText(…).await().indefinitely()}
 * under a friendlier name, the shape qits-ci banned by name. The fan-out runs on the thread that
 * completed the create's transaction; a wait here would let one dead subscriber hold a committed
 * write forever.
 *
 * <p>{@code eventId} is ignored, and that is the contract rather than an omission: this transport's
 * frame <em>is</em> the envelope, so the id is already in the bytes being written. Writing it a
 * second time would be a second place for one value to be read from.
 */
final class WebSocketSink implements EventStreamSink {

  private static final Logger LOG = Logger.getLogger(WebSocketSink.class);

  private final WebSocketConnection connection;

  WebSocketSink(WebSocketConnection connection) {
    this.connection = connection;
  }

  @Override
  public String id() {
    return connection.id();
  }

  @Override
  public boolean isOpen() {
    return connection.isOpen();
  }

  @Override
  public void send(String eventId, String frame) {
    connection
        .sendText(frame)
        .subscribe()
        .with(
            sent -> {},
            failure ->
                LOG.debugf(
                    "Dropped a stream frame for connection %s: %s",
                    connection.id(), failure.getMessage()));
  }
}
