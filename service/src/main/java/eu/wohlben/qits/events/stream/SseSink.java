package eu.wohlben.qits.events.stream;

import io.smallrye.mutiny.subscription.MultiEmitter;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;

/**
 * The {@link EventStreamSink} over a Server-Sent Events response — the transport {@link
 * EventStreamResource} owns, and the one a browser's {@code EventSource} opens.
 *
 * <p>It wraps the Mutiny {@link MultiEmitter} the resource's {@code Multi<OutboundSseEvent>} is
 * built from. {@code emit} hands the event to the emitter's buffer and returns; the write to the
 * socket happens on whatever thread the SSE subscriber is draining on, which is what keeps the
 * fan-out's "never block the thread that committed the write" rule true for this transport too.
 *
 * <p><b>Three deliberate absences in the event this builds, all of them wire-visible.</b>
 *
 * <ul>
 *   <li><b>No media type.</b> RESTEasy Reactive writes a {@code content-type:} field into the SSE
 *       frame if — and only if — the event carries one ({@code OutboundSseEventImpl.isMediaTypeSet}),
 *       and with none set it serializes the data against the resource's stream element type, falling
 *       back to {@code text/plain}. The data here is <em>already</em> a JSON document as a String, so
 *       {@code text/plain} is what writes it verbatim; declaring {@code application/json} would route
 *       it through the Jackson writer and deliver a quoted JSON <em>string</em> — the envelope
 *       escaped inside itself — which is why this route also carries no {@code @RestStreamElementType}.
 *   <li><b>No event name.</b> This stream pushes exactly one shape, so a {@code event:} field would
 *       be a constant, and a browser that listened for it by name would break the moment a second
 *       shape arrived under a different one. Readers take {@code onmessage} and read {@code name}
 *       out of the envelope, which is the same discriminator the websocket transport has always
 *       used.
 *   <li><b>No reconnect delay.</b> {@code retry:} would pin the browser's backoff from here; the
 *       default ({@code EventSource}'s own, typically 3s) is the browser's business, and a stream
 *       that is live-only has nothing to say about how urgently to come back.
 * </ul>
 *
 * <p>{@code id:} <b>is</b> set, and it is the one field that earns its place: it is the event's own
 * id — the same value the JSON carries — and it is what a later catch-up read resumes from. A
 * browser hands the last one back as {@code Last-Event-ID} on reconnect for free, so recording it on
 * the wire today is what keeps that protocol addable without breaking anyone, exactly as the
 * websocket envelope's {@code id} does.
 */
final class SseSink implements EventStreamSink {

  private final String id;
  private final MultiEmitter<? super OutboundSseEvent> emitter;
  private final Sse sse;

  SseSink(String id, MultiEmitter<? super OutboundSseEvent> emitter, Sse sse) {
    this.id = id;
    this.emitter = emitter;
    this.sse = sse;
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public boolean isOpen() {
    return !emitter.isCancelled();
  }

  @Override
  public void send(String eventId, String frame) {
    emitter.emit(sse.newEventBuilder().id(eventId).data(String.class, frame).build());
  }
}
