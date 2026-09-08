package eu.wohlben.qits.events.stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.events.dto.EventCreated;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/**
 * Who is listening, to what, and the fan-out itself — the half of the event stream that is not a
 * transport.
 *
 * <p>Single instance, single process, in-memory: the plan says so and it is not a shortcut waiting
 * to be paid for. A subscription is worth exactly as long as the connection holding it, so there is
 * nothing to replicate; a second instance of this service would need a real broker rather than a
 * shared table, and that is a different feature.
 *
 * <p><b>One table, two transports.</b> {@link EventStreamSocket} registers a {@link WebSocketSink}
 * and {@link EventStreamResource} registers an {@link SseSink}, and from here they are
 * indistinguishable — same map, same {@code AFTER_SUCCESS} observer, same {@link #matches} rule,
 * same single serialization of the envelope. That is the whole point of {@link EventStreamSink}: a
 * browser reader and a service's eventstream jar are being told about the same bus, and two fan-outs
 * would be two chances to disagree about what "live" means.
 */
@ApplicationScoped
public class EventStreamSubscriptions {

  private static final Logger LOG = Logger.getLogger(EventStreamSubscriptions.class);

  /** The signature that means "everything" — {@code {"subscribe": ["*"]}}, or {@code ?names=*}. */
  static final String EVERYTHING = "*";

  /**
   * Connection id → what it asked for. Concurrent because frames, closes and the fan-out all arrive
   * on different threads; the value is replaced wholesale rather than mutated, so a broadcast
   * iterating the map always reads one coherent subscription set rather than one being edited.
   */
  private final Map<String, Subscriber> subscribers = new ConcurrentHashMap<>();

  @Inject ObjectMapper objectMapper;

  private record Subscriber(EventStreamSink sink, Set<String> signatures) {}

  /**
   * Register a reader that has not named anything yet — the websocket's opening state.
   *
   * <p>Subscribed to nothing until it says otherwise: a connection that has not named a signature
   * has not asked for traffic, and a browser tab that merely opened the socket should not get any.
   */
  void opened(EventStreamSink sink) {
    opened(sink, Set.of());
  }

  /**
   * Register a reader that named its interest at connect time — the SSE route, where {@code ?names=}
   * is on the URL because {@code EventSource} has no way to send a frame afterwards.
   *
   * <p>An empty set is legal and means the same thing it means on the socket: silence. It is
   * <em>not</em> shorthand for everything, on either transport, for the same reason — a reader that
   * has not said what it wants has not asked for the whole log.
   */
  void opened(EventStreamSink sink, Set<String> signatures) {
    subscribers.put(sink.id(), new Subscriber(sink, Set.copyOf(signatures)));
  }

  /** Forget a reader. Idempotent: a close racing a drop-on-write must be able to arrive twice. */
  void closed(String connectionId) {
    subscribers.remove(connectionId);
  }

  /**
   * Apply one {@code {"subscribe": [...]}} frame, replacing the connection's set.
   *
   * <p>Replace rather than add: a client that wants less has no other way to say so, and a set that
   * only ever grew would make a long-lived connection's interest a function of its whole history.
   *
   * <p>A frame that is not that shape is dropped with a debug line and the connection is left open —
   * the same stance qits-ci's daemon socket takes, and for the same reason: one malformed frame from
   * a client must not cost the connection.
   *
   * <p>{@code computeIfPresent}, not {@code put}: a frame racing a close must not resurrect an entry
   * that {@link #closed} has already removed, which would leak a dead connection into every
   * subsequent broadcast.
   *
   * <p>There is no SSE counterpart and there cannot be one — a {@code EventSource} is a one-way
   * response body, so that transport's interest is fixed at connect and changed by reconnecting.
   */
  void subscribe(String connectionId, String frame) {
    Set<String> requested;
    try {
      requested = parseSubscribe(frame);
    } catch (RuntimeException notASubscribeFrame) {
      LOG.debugf(
          "Dropped an unreadable frame from stream connection %s: %s",
          connectionId, notASubscribeFrame.getMessage());
      return;
    }
    subscribers.computeIfPresent(
        connectionId, (id, existing) -> new Subscriber(existing.sink(), requested));
    LOG.debugf("Stream connection %s now subscribed to %s", connectionId, requested);
  }

  /**
   * Push a newly created event to everyone who asked for its name.
   *
   * <p><b>{@code AFTER_SUCCESS}</b>, so a create that rolls back pushes nothing. The alternative —
   * observing the fire itself — would broadcast events that never became rows, and a subscriber has
   * no way to un-see one.
   *
   * <p>Nothing here blocks and nothing here throws upwards. The observer runs on the thread that
   * completed the create's transaction, so a slow or dead subscriber must not be able to hold it;
   * every {@link EventStreamSink} owes that guarantee and the two that exist keep it in their own
   * ways (a subscribed {@code Uni} on the socket, a buffered emit on SSE). One broken reader costs
   * its own frame and nothing else — least of all the write that produced it.
   *
   * <p><b>The envelope is serialized once, here, for every reader on every transport.</b> Not once
   * per reader and not once per transport: the bytes a browser reads out of {@code data:} are the
   * bytes a consumer's eventstream jar reads off the socket, and one {@link ObjectMapper} call is
   * what makes that a fact rather than a hope.
   */
  void onEventCreated(@Observes(during = TransactionPhase.AFTER_SUCCESS) EventCreated created) {
    if (subscribers.isEmpty()) {
      return;
    }
    String frame;
    try {
      frame = objectMapper.writeValueAsString(created);
    } catch (Exception unserializable) {
      // Cannot happen for a record of Strings and an Instant, and is logged rather than thrown
      // because the row is already committed: there is no caller left to tell.
      LOG.errorf(unserializable, "Could not serialize event %s for the stream", created.id());
      return;
    }
    for (Subscriber subscriber : subscribers.values()) {
      if (matches(subscriber.signatures(), created.name())) {
        push(subscriber, created.id(), frame);
      }
    }
  }

  private static boolean matches(Set<String> signatures, String name) {
    return signatures.contains(EVERYTHING) || signatures.contains(name);
  }

  /**
   * How many open connections would be pushed an event named {@code name} right now.
   *
   * <p>A seam for the suite and nothing in this service calls it. Neither transport acknowledges a
   * subscription — the socket's frame takes effect when the server gets round to it, and the SSE
   * route is registered when the response's {@code Multi} is subscribed, which is after the method
   * returned — so a test that recorded an event before that had happened would lose the push forever
   * and flake. Public rather than package-private because this bean is normal-scoped: a client proxy
   * forwards public methods only, and a package-private call would land on the uninitialised proxy
   * instance.
   */
  public int subscriberCountFor(String name) {
    return (int) subscribers.values().stream().filter(s -> matches(s.signatures(), name)).count();
  }

  private void push(Subscriber subscriber, String eventId, String frame) {
    EventStreamSink sink = subscriber.sink();
    try {
      if (!sink.isOpen()) {
        // A close this registry has not been told about yet. Drop it here rather than send into it.
        subscribers.remove(sink.id());
        return;
      }
      sink.send(eventId, frame);
    } catch (RuntimeException wontSend) {
      LOG.debugf("Stream connection %s refused a frame: %s", sink.id(), wontSend.getMessage());
    }
  }

  /**
   * The subscribe frame's one shape. Non-textual and blank entries are ignored rather than rejected:
   * the array is a statement of interest, and there is no interest a blank string could express.
   */
  private Set<String> parseSubscribe(String frame) {
    JsonNode root;
    try {
      root = objectMapper.readTree(frame);
    } catch (Exception notJson) {
      throw new IllegalArgumentException("not JSON: " + notJson.getMessage());
    }
    JsonNode subscribe = root == null ? null : root.get("subscribe");
    if (subscribe == null || !subscribe.isArray()) {
      throw new IllegalArgumentException("no 'subscribe' array");
    }
    Set<String> signatures = new HashSet<>();
    for (JsonNode entry : subscribe) {
      if (entry.isTextual() && !entry.asText().isBlank()) {
        signatures.add(entry.asText());
      }
    }
    return Set.copyOf(signatures);
  }

  /**
   * The same statement of interest spelled as a query parameter — {@code ?names=A,B} — which is the
   * only way an {@code EventSource} can make it, since it cannot send a frame.
   *
   * <p>It lives here rather than on the SSE resource so that the <em>vocabulary</em> has one
   * definition even though it has two syntaxes: {@code *} means everything, blank entries express no
   * interest and are ignored, and <b>absent or empty means nothing at all</b> rather than
   * everything. That last clause is the one worth spelling twice — a missing parameter is a reader
   * that has not said what it wants, and answering it with the whole bus would be the opposite of
   * what the socket does for a connection that has not sent a frame.
   *
   * <p>Never a 400. A parameter that names only blanks is a request for silence, and refusing the
   * connection over it would tell a browser nothing it could act on — {@code EventSource} surfaces a
   * failed connect as an untyped error and then retries it forever.
   */
  static Set<String> parseNames(String names) {
    if (names == null || names.isBlank()) {
      return Set.of();
    }
    Set<String> signatures = new HashSet<>();
    for (String entry : names.split(",")) {
      if (!entry.isBlank()) {
        signatures.add(entry.trim());
      }
    }
    return Set.copyOf(signatures);
  }
}
