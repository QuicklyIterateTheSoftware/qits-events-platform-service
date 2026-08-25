package eu.wohlben.qits.events.api;

import eu.wohlben.qits.events.control.EventQuery;
import eu.wohlben.qits.events.control.EventService;
import eu.wohlben.qits.events.dto.EventDto;
import eu.wohlben.qits.events.entity.Event;
import eu.wohlben.qits.events.mapper.EventMapper;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.List;
import org.jboss.resteasy.reactive.RestResponse;

/**
 * The event log: list, read, record, publish, remove.
 *
 * <p>Served under {@code /events/api/events} — the {@code /events/api} prefix is
 * {@code quarkus.rest.path}, not spelled here, so this class carries only its own noun.
 *
 * <p>Request and response shapes are nested records, the platform's controller idiom: the wire
 * contract for one operation lives beside the method that serves it, and the generated OpenAPI
 * document names them after the operation rather than after a bag of shared DTOs.
 *
 * <p><b>There are two writes and they are not two spellings of one.</b> {@code POST} records an
 * event under an id this service picks — the manual path, for a person or a script with nothing to
 * retry. {@code PUT /{id}} is the bus's publish: the id is the <em>publisher's</em> UUID and is the
 * idempotency key, which is what lets a publisher that lost the answer to its first attempt send the
 * same bytes again and be told 200 rather than write a second row.
 */
@Path("/events")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EventController {

  @Inject EventService eventService;

  @Inject EventMapper eventMapper;

  /**
   * A page of the log, and where the next one resumes.
   *
   * <p>{@code nextCursor} is {@code null} on the last page, and that is the only end-of-log signal a
   * client needs — a full page is not one, and a count would be a second question this route
   * deliberately does not answer. The field was <em>appended</em> to a shape that already shipped,
   * so a consumer that never reads it goes on reading the list it always read.
   */
  public record ListEventsRequest() {
    public record Response(List<EventDto> events, String nextCursor) {}
  }

  /**
   * The log, newest first, one page at a time — and, with {@code ?parentId=<id>}, the events that
   * <em>one</em> event caused, which is the downward half of a chain walk.
   *
   * <p>Every filter is a query parameter on the route that already exists rather than a route of its
   * own, and that is not only economy: a new literal under {@code /events} would need an entry in
   * {@code quarkus.quinoa.ignored-path-prefixes} in the same commit, which is this platform's
   * standing trap. Parameters need none, so none of this touches that key.
   *
   * <ul>
   *   <li>{@code ?limit=} — page size. Absent is 200, above 1000 is 1000, and anything that is not a
   *       whole number at least 1 is a 400. It was silently <em>unknown</em> before this — the
   *       method declared {@code parentId} alone, so JAX-RS dropped it and answered with all of
   *       history while the client believed it had asked for ten rows.
   *   <li>{@code ?cursor=<occurredAt>,<id>} — resume after the previous page's last row. Composite
   *       because {@code occurredAt} ties; see {@code EventCursor}.
   *   <li>{@code ?order=asc} — oldest first. Absent or {@code desc} is the reading this route has
   *       always answered, byte for byte. Ascending exists for <b>durable consumers catching up</b>:
   *       a consumer that keeps the last row it handled as a watermark reads <em>forward</em> from
   *       it until it reaches the head, which descending cannot express. The cursor is the same
   *       value in both directions — the page's last row — and the comparison flips with the sort,
   *       so a tie splits across a page boundary exactly as safely; see {@code EventOrder}. Anything
   *       that is neither spelling is a 400 naming the parameter, because answering with the
   *       opposite direction would let a catch-up consumer record a watermark it never reached.
   *   <li>{@code ?name=A,B} — the same vocabulary the stream's subscribe frame uses, so a filter
   *       means one thing live and historically. {@code GET /events/names} lists it.
   *   <li>{@code ?since=} — an inclusive lower bound on {@code occurredAt}. There is no upper bound
   *       parameter; the cursor is the upper bound.
   *   <li>{@code ?q=} — a case-insensitive substring of the payload, which this service stores and
   *       hands back as an opaque string and does not parse here either.
   *   <li>{@code ?attr=<key>=<value>} — repeatable, ANDed. An exact match of the fragment {@code
   *       "key":"value"} in the payload, case-insensitive. This service still parses no payload: it
   *       builds a literal whose shape the platform's own canonical-JSON publisher guarantees, so it
   *       works for string-valued keys of events published through {@code CanonicalJson} and nothing
   *       else, and it is a scan like {@code ?q=} rather than an indexed lookup. A value with no
   *       {@code =} is a 400 naming the parameter.
   *   <li>{@code ?environment=} — exact match on the tier the publisher stamped ({@code dev},
   *       {@code platform}). Envelope data on its own column, so unlike {@code ?q=} and {@code
   *       ?attr=} it is an indexed equality, not a payload scan. A value that could never have been
   *       stored — not a dns-safe name — is a 400 naming the parameter. Events recorded before the
   *       platform knew tiers carry null and match no filter value.
   * </ul>
   *
   * <p><b>{@code ?parentId=} is answered whole and takes none of them.</b> A parent's children are
   * one per artifact a pipeline declares — bounded by a file in a repository, not by history — so
   * paging them would be a parameter that never fires, and {@code nextCursor} is null on that
   * answer. An unknown parent gives an empty list, not a 404; blank is treated as absent, so {@code
   * ?parentId=} is a client that meant to ask for everything.
   *
   * <p>There is deliberately no chain, depth or root endpoint; upwards is {@code GET /{id}}
   * following {@code parentId}, downwards is this, and a client that walks either <b>must bound its
   * own depth and remember the ids it has seen</b>, because nothing here prevents a cycle.
  */
  @GET
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:system"})
  public ListEventsRequest.Response list(
      @QueryParam("parentId") String parentId,
      @QueryParam("name") String name,
      @QueryParam("since") String since,
      @QueryParam("q") String q,
      @QueryParam("attr") List<String> attr,
      @QueryParam("environment") String environment,
      @QueryParam("cursor") String cursor,
      @QueryParam("order") String order,
      @QueryParam("limit") String limit) {
    if (parentId != null && !parentId.isBlank()) {
      return new ListEventsRequest.Response(toDtos(eventService.listChildrenOf(parentId)), null);
    }
    var page =
        eventService.list(EventQuery.of(name, since, q, cursor, limit, attr, order, environment));
    return new ListEventsRequest.Response(
        toDtos(page.events()),
        page.nextCursor() == null ? null : page.nextCursor().format());
  }

  public record ListEventNamesRequest() {
    public record Response(List<String> names) {}
  }

  /**
   * The names the log holds, once each, alphabetically — what a filter can offer and what a
   * subscriber may name in its subscribe frame.
   *
   * <p><b>It is a literal beside a template, and the order they are matched in is the hazard.</b>
   * {@code /names} and {@code /{id}} are siblings under this class's {@code @Path}, and JAX-RS sorts
   * literal characters ahead of a template — so {@code /events/api/events/names} reaches this method
   * and not {@code get("names")}, which would be a 404 for an event nobody recorded. That is a spec
   * guarantee being leaned on rather than a local arrangement, so {@code EventApiTest} asserts it;
   * if it ever stops holding, a second controller at {@code /event-names} settles the question with
   * no ordering in it at all.
   */
  @GET
  @Path("/names")
  @jakarta.annotation.security.RolesAllowed("qits:admin")
  public ListEventNamesRequest.Response names() {
    return new ListEventNamesRequest.Response(eventService.names());
  }

  private List<EventDto> toDtos(List<Event> events) {
    return events.stream().map(eventMapper::toDto).toList();
  }

  public record GetEventRequest() {
    public record Response(EventDto event) {}
  }

  @GET
  @Path("/{id}")
  @jakarta.annotation.security.RolesAllowed("qits:admin")
  public GetEventRequest.Response get(@PathParam("id") String id) {
    return new GetEventRequest.Response(eventMapper.toDto(eventService.get(id)));
  }

  /**
   * {@code occurredAt} is optional and defaults to now. It is the caller's time — recording
   * something that already happened is the normal case — so a value in the past is accepted as it
   * stands. {@code payload} is optional too: an event recorded by hand is honestly nothing but a
   * name and a time.
   *
   * <p>{@code parentId} is optional here too, and validated exactly as it is on {@code PUT}: a
   * canonical UUID if present, never the new event's own id. A person recording by hand rarely names
   * a cause, but a field the bus accepts and this path dropped would be two definitions of an
   * envelope behind one entity. {@code environment} follows for the same reason, under the same
   * shape guard as {@code PUT}.
   */
  public record CreateEventRequest(
      @NotBlank String name,
      Instant occurredAt,
      String payload,
      String description,
      String parentId,
      String environment) {
    public record Response(EventDto event) {}
  }

  @POST
  @jakarta.annotation.security.RolesAllowed("qits:admin")
  public CreateEventRequest.Response create(@Valid CreateEventRequest request) {
    var event =
        eventService.create(
            request.name(),
            request.occurredAt(),
            request.payload(),
            request.description(),
            request.parentId(),
            request.environment());
    return new CreateEventRequest.Response(eventMapper.toDto(event));
  }

  /**
   * The publish envelope — the same fields the {@code /events/stream} frame carries, minus the id,
   * which is in the path.
   *
   * <p>{@code occurredAt} is <b>required</b> here, unlike on {@code POST}: it is one of the fields a
   * replay is compared on, so an event whose time this server invented could never replay equal to
   * itself. {@code payload} arrives as canonical JSON <em>in a string</em> and is stored and compared
   * verbatim — this server does not canonicalize, the publisher does.
   *
   * <p>{@code parentId} is the id of the event that caused this one. <b>Absent is legal and means
   * null</b> — a publisher that never learned about the field keeps working, which is the whole of
   * this contract's backward compatibility and the reason this service ships before any publisher
   * that stamps. It is <em>inside</em> the replay comparison, though, so a second PUT of one id
   * under a different cause is a 400 rather than a silent disagreement about history.
   *
   * <p>{@code environment} is the tier the publisher ran in ({@code dev}, or {@code platform} for a
   * publisher serving every tier). Same clauses as the parent: absent is legal and means null — the
   * value of every event published before the field existed — and it is inside the replay
   * comparison, because one id claiming two tiers is two claims about history.
   */
  public record PublishEventRequest(
      @NotBlank String name,
      @NotNull Instant occurredAt,
      String payload,
      String description,
      String parentId,
      String environment) {
    public record Response(EventDto event) {}
  }

  /**
   * Idempotent publish under the caller's UUID.
   *
   * <ul>
   *   <li><b>201</b> — the id was unknown; the row was created and pushed to matching subscribers
   *   <li><b>200</b> — the id was known and {@code name}/{@code occurredAt}/{@code payload}/{@code
   *       parentId}/{@code environment} match exactly: the same event arriving twice. Nothing
   *       written, nothing pushed
   *   <li><b>400</b> — the id was known and something differs (a reused UUID, which no retry fixes),
   *       or the id is not a UUID at all, or {@code parentId} is not a UUID, or {@code parentId} is
   *       the event's own id
   * </ul>
   *
   * <p>A {@code parentId} naming an event this log does not have is <b>not</b> one of them: it is
   * stored as it stands, because nothing orders a parent's arrival before its child's. See {@code
   * EventService}.
   *
   * <p>{@code RestResponse<T>} rather than a bare {@code Response}: the status has to vary and the
   * body type has to stay visible to the OpenAPI document, and only the typed form gives both.
   */
  @PUT
  @Path("/{id}")
  @jakarta.annotation.security.RolesAllowed("qits:system")
  public RestResponse<PublishEventRequest.Response> publish(
      @PathParam("id") String id, @Valid PublishEventRequest request) {
    var published =
        eventService.publish(
            id,
            request.name(),
            request.occurredAt(),
            request.payload(),
            request.description(),
            request.parentId(),
            request.environment());
    var body = new PublishEventRequest.Response(eventMapper.toDto(published.event()));
    return RestResponse.status(
        published.outcome() == EventService.PublishOutcome.CREATED
            ? Response.Status.CREATED
            : Response.Status.OK,
        body);
  }

  public record DeleteEventRequest() {
    public record Response(boolean success) {}
  }

  @DELETE
  @Path("/{id}")
  @jakarta.annotation.security.RolesAllowed("qits:admin")
  public DeleteEventRequest.Response delete(@PathParam("id") String id) {
    eventService.delete(id);
    return new DeleteEventRequest.Response(true);
  }
}
