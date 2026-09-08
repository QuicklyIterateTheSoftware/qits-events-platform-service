package eu.wohlben.qits.events.stream;

/**
 * One open reader of the event stream, seen by the fan-out — everything {@link
 * EventStreamSubscriptions} needs to know about a connection, and nothing about how that connection
 * is carried.
 *
 * <p><b>This interface exists so there is one subscription table and not two.</b> The stream has two
 * transports now — the {@code @WebSocket} at {@code /events/stream} that every sibling service's
 * eventstream jar dials, and the Server-Sent Events route at {@code /events/api/stream} that a
 * browser's {@code EventSource} opens — and they differ in exactly three things: how a client says
 * what it wants, how a frame is written, and how the connection ends. Everything else is shared: the
 * signature vocabulary, the {@code *} wildcard, "subscribed to nothing until you say otherwise",
 * matching, the {@code AFTER_SUCCESS} observer, one serialization of {@link
 * eu.wohlben.qits.events.dto.EventCreated}. A second implementation of that would be a second place
 * for the bus's live semantics to be wrong in, and the two would drift the first time either was
 * touched.
 *
 * <p>Three methods, and each is one of the three questions the fan-out asks:
 *
 * <ul>
 *   <li>{@link #id()} — the key this reader is held under. It has to be stable for the whole life of
 *       the connection, because a subscribe frame and a close both find the entry by it.
 *   <li>{@link #isOpen()} — a cheap "is this worth writing to", checked immediately before a push.
 *       It is not a guarantee: a connection can die between the check and the write, which is why
 *       the write itself must also survive a dead peer.
 *   <li>{@link #send(String, String)} — write one frame, <b>without blocking and without
 *       throwing</b>. The fan-out runs on the thread that completed the create's transaction, so an
 *       implementation that waited on the wire would hold a committed write hostage to the slowest
 *       reader on the bus. Failure is the implementation's own to log and drop; one broken reader
 *       costs its own frame and nothing else.
 * </ul>
 *
 * <p><b>{@code send} takes the event id beside the frame</b>, rather than the frame alone, because
 * the two transports carry it in different places. The websocket carries it only inside the JSON —
 * the frame <em>is</em> the envelope — while SSE has an {@code id:} field of its own that the
 * protocol reserves for exactly this, and that a client sends back as {@code Last-Event-ID} when it
 * reconnects. Handing both to the sink lets the SSE side fill that field without re-parsing the JSON
 * it was just given, and costs the websocket side an ignored parameter. The id is the same value in
 * both places; it is not two ids.
 */
interface EventStreamSink {

  /** The key this reader is registered under, stable for the life of the connection. */
  String id();

  /** A cheap pre-check, not a promise: a connection can still die before the next write lands. */
  boolean isOpen();

  /**
   * Write one frame. Never blocks, never throws upwards — see the class comment.
   *
   * @param eventId the id of the event this frame describes, for transports with a field for it
   * @param frame the serialized {@link eu.wohlben.qits.events.dto.EventCreated}, one per transport
   *     and byte-identical across them
   */
  void send(String eventId, String frame);
}
