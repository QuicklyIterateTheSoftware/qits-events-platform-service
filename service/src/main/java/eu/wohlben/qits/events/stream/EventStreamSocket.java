package eu.wohlben.qits.events.stream;

import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.inject.Inject;

/**
 * The way out of the event log: a connection listens here and is pushed every newly created event
 * whose {@code name} it asked for.
 *
 * <p>It owns the socket lifecycle and nothing else — {@link EventStreamSubscriptions} owns the
 * table of who wants what and the fan-out — which is the split {@code CiDaemonSocket} /
 * {@code CiDaemonRegistry} carries in qits-ci, kept deliberately.
 *
 * <p><b>This is one of two transports over that one table, and it is the machine's.</b> {@link
 * EventStreamResource} serves the same fan-out as Server-Sent Events at {@code /events/api/stream}
 * for a browser, which cannot open a websocket carrying its session as easily as it can open an
 * {@code EventSource}. Nothing about the frame protocol below is shared with it beyond the
 * signature vocabulary and the envelope — and nothing about it may change because that route
 * exists: every consumer on the platform dials here.
 *
 * <p><b>The path literal carries {@code /events} itself.</b> A {@code @WebSocket} path registers
 * straight onto the router and does <em>not</em> follow {@code quarkus.rest.path}, so the segment
 * every route of this service must serve is spelled here. {@code stream} is a second-level segment
 * beside {@code api} because this is not a JSON API. Two things move with this literal and must move
 * in the same commit:
 *
 * <ul>
 *   <li>{@code quarkus.quinoa.ignored-path-prefixes} gains {@code /stream} (relative — the values
 *       are matched after {@code ui-root-path} is stripped). websockets-next claims only the
 *       <em>upgrade handshake</em>, so without that entry a plain {@code GET /events/stream}
 *       falls through to the SPA and answers {@code 200 text/html} — measured on qits-ci, where a
 *       machine path served {@code index.html} from a build that was green.
 *   <li>{@code PackagedSurfaceIT}, which is the only test that can see either fact: Quinoa is off in
 *       test mode, and websockets-next registers this endpoint at <em>augmentation</em>, so "the
 *       route survived the native image" is a claim only the artifact can settle.
 * </ul>
 *
 * <p><b>The protocol is one frame in, many frames out.</b> A client sends {@code {"subscribe":
 * ["BuildSuccessful", …]}}, which <em>replaces</em> that connection's set; {@code ["*"]} means
 * everything. Until it sends one it is subscribed to nothing — silence is the honest default for a
 * connection that has not said what it wants, and it keeps a browser that merely opened the socket
 * out of the fan-out. The server then pushes {@link eu.wohlben.qits.events.dto.EventCreated} as
 * text, live only: no replay, no offset, no catch-up. That is a deliberate omission rather than a
 * gap — catch-up reads the event log itself and is the next feature — and the envelope carries the
 * id precisely so it can be built without breaking anyone.
 *
 * <p>An undecodable frame costs the frame and not the connection. The handlers run on worker
 * threads (websockets-next' default for a blocking-shaped method), so nothing here occupies an event
 * loop.
 */
@WebSocket(path = "/events/stream")
@jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:system"})
public class EventStreamSocket {

  @Inject EventStreamSubscriptions subscriptions;

  @OnOpen
  public void onOpen(WebSocketConnection connection) {
    subscriptions.opened(new WebSocketSink(connection));
  }

  @OnTextMessage
  public void onMessage(String message, WebSocketConnection connection) {
    subscriptions.subscribe(connection.id(), message);
  }

  @OnClose
  public void onClose(WebSocketConnection connection) {
    subscriptions.closed(connection.id());
  }
}
