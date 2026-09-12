package eu.wohlben.qits.events.stream;

import io.smallrye.mutiny.Multi;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * The event stream as <b>Server-Sent Events</b>, for a reader that is a browser tab:
 * {@code GET /events/api/stream?names=BuildSuccessful,BuildFailed}.
 *
 * <p>It is a second <em>transport</em> and not a second stream. {@link EventStreamSubscriptions}
 * holds one subscription table, fires one {@code AFTER_SUCCESS} observer and serializes one
 * envelope; this route registers an {@link SseSink} in that table exactly as {@link
 * EventStreamSocket} registers a {@link WebSocketSink}, so a browser and a consumer service are
 * being told about the same bus by the same code. Whatever this route pushes, the socket pushed.
 *
 * <h2>Why a second transport at all</h2>
 *
 * <p>Because the browser cannot cheaply have the first one. The websocket at {@code /events/stream}
 * is dialled by every sibling service's eventstream jar with the forward-auth headers; a page in the
 * shell has a <em>session cookie</em>, and {@code EventSource} — unlike the browser's {@code
 * WebSocket}, which cannot carry custom headers and does not follow the same auth story — is a plain
 * credentialed {@code GET}. The edge's forward-auth turns exactly that cookie into
 * {@code X-Qits-User} / {@code X-Qits-Roles}, so a browser session reaches this route with nothing
 * new anywhere. (A person's command-line tool reaches it with a bearer token instead.)
 *
 * <h2>The subscription is in the URL, because it has nowhere else to be</h2>
 *
 * <p>{@code EventSource} is one-way: there is no send, so there is no subscribe frame. {@code
 * ?names=} is that frame, spelled as a query parameter, and it means what the frame means —
 * {@link EventStreamSubscriptions#parseNames} is the one definition of the vocabulary:
 *
 * <ul>
 *   <li>{@code ?names=A,B} — those signatures
 *   <li>{@code ?names=*} — everything
 *   <li><b>absent, empty, or nothing but blanks — nothing.</b> Silence for a reader that has not
 *       said what it wants, exactly as an open socket that has sent no frame gets silence. It is
 *       never a 400: a request for silence is a coherent request, and a refused connect tells {@code
 *       EventSource} nothing it can act on — it surfaces an untyped error and retries forever.
 * </ul>
 *
 * <p>A reader that wants <em>different</em> signatures reconnects. That is the one thing this
 * transport cannot do that the socket can, and it is the protocol's own limit rather than a
 * shortcut: there is no channel to say it on.
 *
 * <h2>The path costs no configuration, and that was checked rather than assumed</h2>
 *
 * <p>This is the one difference from the socket that decided the address. {@code
 * @WebSocket("/events/stream")} registers straight onto the Vert.x router, follows {@code
 * quarkus.rest.path} for nothing, and needed {@code quarkus.quinoa.ignored-path-prefixes} moved in
 * the same commit. A JAX-RS {@code @Path} <em>does</em> follow {@code quarkus.rest.path}, so {@code
 * "/stream"} here is served at {@code /events/api/stream} — and both of the things that would
 * otherwise have to move already cover it:
 *
 * <ul>
 *   <li>{@code quarkus.quinoa.ignored-path-prefixes=/events} in {@code application.properties} is a
 *       <em>prefix</em> match against the path as it arrives (the ui-root is {@code /}), so the SPA
 *       catch-all does not reroute anything under {@code /events} — {@code /events/api/stream}
 *       included. <b>This matters more here than it did for the socket</b>: this route is answered
 *       by an ordinary {@code GET}, which is precisely the request that fell through to
 *       {@code index.html} on qits-ci's daemon path. {@code PackagedSurfaceIT} pins it, because
 *       Quinoa is disabled under {@code @QuarkusTest} and no unit test can see it.
 *   <li>{@code routes: /events} in {@code .config/qits/deployments.yml} is what the edge forwards
 *       here by prefix, so the browser reaches this address on this service's own host and on any
 *       sibling's.
 * </ul>
 *
 * <h2>Live only, and the {@code id:} field is why that stays true later</h2>
 *
 * <p>No replay, no offset, no catch-up — the socket's contract, unchanged. But every frame carries
 * the event's own id in the SSE {@code id:} field, which the protocol reserves for exactly this and
 * which a browser hands back as {@code Last-Event-ID} when it reconnects. Catch-up, when it is
 * built, is a read of the log ({@code GET /events/api/events?order=asc&cursor=…}) resuming from that
 * value; recording it on the wire today is what keeps that addable without breaking anyone, the same
 * argument that put {@code id} on the envelope.
 *
 * <h2>The two comment lines</h2>
 *
 * <p>Both are SSE comments ({@code : text}), which the protocol defines as ignorable payload and
 * which no {@code EventSource} listener ever sees. They exist for the transport, not the reader.
 *
 * <ul>
 *   <li><b>One at connect.</b> The response headers of a {@code Multi}-backed SSE route are written
 *       with the first event, so a reader subscribed to a quiet signature would sit on an unanswered
 *       request — indistinguishable from a hung server, and {@code EventSource.onopen} would never
 *       fire. The opening comment flushes the head, so "connected" is observable immediately and
 *       separately from "an event happened".
 *   <li><b>One every {@value #KEEPALIVE_SECONDS} seconds.</b> An idle SSE response is an open
 *       connection with no bytes on it, which a proxy, a load balancer or a browser's own idle
 *       timeout will eventually reap; the reader then reconnects and, on a live-only stream, misses
 *       whatever happened in the gap. A periodic byte keeps the connection accountably alive. It is
 *       cheap and it is not a heartbeat the client is asked to answer.
 * </ul>
 *
 * <h2>The door</h2>
 *
 * <p>{@code @RolesAllowed({"qits:admin", "qits:system"})} — the socket's roles exactly, and the same
 * pair the browser chrome's existing {@code /ci/api/runs/active} read already needs.
 *
 * <p><b>A reader without them is refused at connect (403) rather than handed an empty stream.</b>
 * That was a decision and not the default falling out: an empty stream is byte-for-byte
 * indistinguishable from an idle estate, so a chrome that silently got one would show a permanently
 * calm bolt and never learn it was blind. A 403 is a fact the caller can act on, and the chrome's
 * fallback path — the polled read it already has — is what handles it.
 *
 * <h2>Backpressure</h2>
 *
 * <p>The emitter buffers {@value #BUFFERED_FRAMES} frames. Past that the stream <em>fails</em> and
 * the response is closed, and the reader's {@code EventSource} reconnects on its own. That is the
 * honest end for a live-only stream: a reader that has fallen a few hundred frames behind has
 * already lost its place, and the alternatives are worse in both directions — an unbounded buffer
 * makes one stuck tab a heap problem for the whole bus, and silently dropping frames leaves a reader
 * confidently wrong about what happened.
 */
@Path("/stream")
public class EventStreamResource {

  /**
   * How many frames may sit between the fan-out and a reader's socket before the stream is ended.
   * See the class comment: the number is a judgement, the behaviour at the edge of it is not.
   */
  private static final int BUFFERED_FRAMES = 256;

  /** Spelled as a constant because the class comment quotes it with {@code @value}. */
  private static final int KEEPALIVE_SECONDS = 20;

  private static final Duration KEEPALIVE = Duration.ofSeconds(KEEPALIVE_SECONDS);

  @Inject EventStreamSubscriptions subscriptions;

  /**
   * The SSE event builder. {@code @ApplicationScoped} in RESTEasy Reactive's own producer and
   * stateless behind it, so holding it in a field and using it from the fan-out's thread — long
   * after the request that opened the stream returned — is sound.
   */
  @Inject Sse sse;

  /**
   * Open a live stream of the signatures {@code names} asks for.
   *
   * <p>The returned {@code Multi} never completes on its own: the stream ends when the reader goes
   * away, which cancels it, which is what unregisters the sink. That is the only close there is —
   * there is no "unsubscribe" for a transport whose client cannot speak.
   *
   * <p><b>Two streams merged, and the shapes are load-bearing.</b> The first is the fan-out itself,
   * an emitter whose {@link SseSink} is registered in the subscription table when this response is
   * subscribed — <em>after</em> this method returned, which is why {@link
   * EventStreamSubscriptions#subscriberCountFor} exists for the suite. The second is the keepalive
   * tick. Merging rather than, say, a timer writing into the same emitter keeps the periodic frame
   * out of the buffer that the fan-out's backpressure is measured against, and lets one cancellation
   * take both down.
   *
   * <p>No {@code @RestStreamElementType}: it would become the media type the data is serialized
   * against, and {@code application/json} there would hand the envelope to the Jackson writer and
   * deliver it quoted inside itself. See {@link SseSink}.
   */
  @GET
  @Produces(MediaType.SERVER_SENT_EVENTS)
  @RolesAllowed({"qits:admin", "qits:system"})
  @Operation(
      summary = "Live event stream (Server-Sent Events)",
      description =
          "Pushes each newly created event whose name is named by ?names= (comma-separated; '*'"
              + " means everything; absent means nothing) as an SSE event whose id: field is the"
              + " event id and whose data is the EventCreated envelope. Live only: no replay, no"
              + " offset, no catch-up.")
  @APIResponse(
      responseCode = "200",
      description =
          "An open SSE stream. Each event carries the event id in its id: field and the"
              + " EventCreated envelope's JSON in its data: field; comment lines mark the open and"
              + " keep the connection alive. The body never ends.",
      // Declared rather than derived. Left to the scanner, the method's Multi<OutboundSseEvent>
      // return type publishes jakarta.ws.rs.sse.OutboundSseEvent and jakarta.ws.rs.core.MediaType
      // as component schemas — the SERVER's framing objects, which no caller ever sees on the wire
      // and which say nothing at all about this stream. A text stream is what a reader gets.
      content = @Content(mediaType = MediaType.SERVER_SENT_EVENTS, schema = @Schema(type = SchemaType.STRING)))
  public Multi<OutboundSseEvent> stream(@QueryParam("names") String names) {
    Set<String> signatures = EventStreamSubscriptions.parseNames(names);
    // Its own id space: the table is keyed by connection, and a websocket connection id is Vert.x'
    // to mint. The prefix is for a debug line's reader and for nothing else.
    String connectionId = "sse-" + UUID.randomUUID();

    Multi<OutboundSseEvent> frames =
        Multi.createFrom()
            .<OutboundSseEvent>emitter(
                emitter -> {
                  // Registered BEFORE the sink, so a reader that vanishes during connect cannot
                  // leave an entry behind: termination is the only thing that removes one.
                  emitter.onTermination(() -> subscriptions.closed(connectionId));
                  emitter.emit(sse.newEventBuilder().comment("open").build());
                  subscriptions.opened(new SseSink(connectionId, emitter, sse), signatures);
                },
                BUFFERED_FRAMES);

    Multi<OutboundSseEvent> keepalives =
        Multi.createFrom()
            .ticks()
            .every(KEEPALIVE)
            .onOverflow()
            .drop()
            .onItem()
            .transform(tick -> sse.newEventBuilder().comment("keepalive").build());

    return Multi.createBy().merging().streams(frames, keepalives);
  }
}
