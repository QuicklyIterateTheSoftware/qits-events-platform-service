package eu.wohlben.qits.events.control;

import eu.wohlben.qits.db.DbRetry;
import eu.wohlben.qits.events.dto.EventCreated;
import eu.wohlben.qits.events.entity.Event;
import eu.wohlben.qits.events.error.BadRequestException;
import eu.wohlben.qits.events.error.NotFoundException;
import eu.wohlben.qits.events.persistence.EventRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The event log's behaviour: read it, record into it, and — since this context became the platform's
 * bus — accept a publisher's idempotent write.
 *
 * <p>{@code occurredAt} defaults to now when the caller omits it on {@link #create}, and is
 * otherwise taken verbatim, including a value in the past: recording something that already happened
 * is the normal case, not an edge one.
 *
 * <p><b>Every create announces itself</b> through the CDI event {@link EventCreated}, which the
 * {@code service} module's websocket registry observes {@code AFTER_SUCCESS} and fans out to
 * subscribers. The signal is fired from here rather than from the boundary so that both write paths
 * — the manual {@code POST} and the publisher's {@code PUT} — cannot diverge about it, and it is a
 * CDI event rather than a direct call because this module carries no web stack and must not learn
 * about one to notify anybody.
 */
@ApplicationScoped
public class EventService {

  @Inject EventRepository eventRepository;

  /**
   * Fired on create and on nothing else. Declared with the fully-qualified type because {@code
   * Event} in this file is the entity — the collision is worth one long name rather than an import
   * alias that would make every other line ambiguous to a reader.
   */
  @Inject jakarta.enterprise.event.Event<EventCreated> created;

  /** What an idempotent publish did, which is the whole of what the boundary needs to pick 201/200. */
  public enum PublishOutcome {
    CREATED,
    REPLAYED
  }

  /** The stored event plus what happened to it. */
  public record Published(Event event, PublishOutcome outcome) {}

  /**
   * One page of the log and where the next one starts, or {@code null} when this was the last page.
   *
   * <p>The cursor is <b>the page's own last row</b> rather than a token this service remembers:
   * nothing is stored, nothing expires, and a client that keeps a cursor for a week resumes exactly
   * where it stopped. An append-only log makes that safe — rows arrive at the head, and a walk
   * downwards can only be overtaken, never invalidated. It is the page's last row in <b>either</b>
   * direction, which is what lets a catch-up consumer keep it as a watermark and send it back
   * verbatim: ascending, the rows it has not seen are the ones after it.
   */
  public record EventPage(List<Event> events, EventCursor nextCursor) {}

  /**
   * One page of the log, in the direction the query asks for — newest first unless it says {@code
   * asc}.
   *
   * <p>The repository is asked for one row more than the page holds. If it comes back, there is more
   * history and the page's last row becomes the next cursor; if it does not, the client has reached
   * the end and gets {@code null} — which is the only thing it has to check, and it never has to
   * infer the answer from a page that happened to come back full.
   */
  public EventPage list(EventQuery query) {
    List<Event> rows = eventRepository.listPage(query);
    boolean more = rows.size() > query.limit();
    List<Event> page = List.copyOf(more ? rows.subList(0, query.limit()) : rows);
    if (!more || page.isEmpty()) {
      return new EventPage(page, null);
    }
    Event last = page.get(page.size() - 1);
    return new EventPage(page, new EventCursor(last.occurredAt, last.id));
  }

  /** The first page at the default size — the whole log, while the log is smaller than a page. */
  public List<Event> list() {
    return list(EventQuery.defaults()).events();
  }

  /** Every name the log holds, once each, alphabetically — the filter's and the bus's vocabulary. */
  public List<String> names() {
    return eventRepository.distinctNames();
  }

  /**
   * The events this one caused, newest first — the downward half of a chain walk.
   *
   * <p>An id no row names as its parent gives an empty list, never a 404: this service does not
   * know whether such an id is wrong, not here yet, or from a publisher it has never heard from, and
   * "nothing was caused by it as far as I know" is the true answer to the question in every case.
   */
  public List<Event> listChildrenOf(String parentId) {
    return eventRepository.listChildrenOf(parentId);
  }

  public Event get(String id) {
    return eventRepository
        .findByIdOptional(id)
        .orElseThrow(() -> new NotFoundException("Event not found: " + id));
  }

  /**
   * Record an event under an id of this service's choosing — the manual path.
   *
   * <p>It takes a {@code parentId} too, and validates it exactly as {@link #publish} does. A person
   * recording by hand rarely has a cause to name, but the two write paths must not be able to
   * disagree about what an event <em>is</em>: a field the bus accepts and the manual path silently
   * dropped would be a second definition of the envelope hiding behind one entity.
   *
   * <p>The insert runs inside {@link DbRetry#inNewTx}, so a connection lost mid-statement costs a
   * pause rather than a 500. Validation and the id run <em>outside</em> it: a 400 should not open a
   * transaction, and the retried body has to be database work and nothing else.
   */
  public Event create(
      String name,
      Instant occurredAt,
      String payload,
      String description,
      String parentId,
      String environment) {
    Validations.requireText(name, "name");
    String id = UUID.randomUUID().toString();
    Instant when = atStoredPrecision(occurredAt == null ? Instant.now() : occurredAt);
    String cause = causeOf(id, parentId);
    String tier = environmentOf(environment);
    return DbRetry.inNewTx(
        "record an event", () -> store(id, name, when, payload, description, cause, tier));
  }

  /**
   * Record an event under the <b>publisher's</b> id — the bus's write path, and the reason a retry
   * is safe.
   *
   * <p>Three outcomes, and there is no fourth:
   *
   * <ul>
   *   <li>the id is unknown → the row is created and announced ({@link PublishOutcome#CREATED},
   *       201);
   *   <li>the id is known and {@code name}, {@code occurredAt}, {@code payload}, {@code parentId}
   *       and {@code environment} are all exactly equal → this is the same event arriving twice,
   *       because the publisher's first attempt got no answer. Nothing is written and <b>nothing is
   *       announced</b> ({@link PublishOutcome#REPLAYED}, 200): a subscriber must not see an event
   *       twice because a network dropped an acknowledgement;
   *   <li>the id is known and any of the five differs → the caller reused a UUID, which is not
   *       something a retry can fix, so it is a 400 rather than a conflict to sit and poll on.
   * </ul>
   *
   * <p><b>{@code description} is deliberately outside the comparison; {@code parentId} and {@code
   * environment} are deliberately inside it.</b> The line is identity of the occurrence versus
   * prose about it. Description is the human account; a publisher that improves its wording on a
   * retry has not published a different event. A parent is on the identity side: it is
   * machine-consumed structure, it is the edge a chain is drawn from, and two PUTs of one id
   * claiming different causes are two different claims about history — kept outside, the server
   * would silently keep the first and answer 200 while the publisher believed it had published the
   * second, which is two services disagreeing about the shape of history with no error anywhere.
   * The environment sits on the same side by the same argument: which tier something happened in is
   * machine-consumed structure, and one id claiming two tiers is two claims about history. The
   * strictness costs a well-behaved publisher nothing: an outbox stores the envelope whole, so its
   * own two attempts cannot disagree.
   *
   * <p>The stored description is left as it was — a replay writes nothing at all, which is what
   * makes 200 free of a transaction.
   *
   * <p>{@code payload} is compared as an opaque string. This server never canonicalizes it; the
   * publisher does, and both sides of the equality are therefore the publisher's own bytes.
   *
   * <p><b>This is the platform's hub write</b> — every outbox in the fleet retries against it — so
   * the whole decision runs inside {@link DbRetry#inNewTx}. A connection lost while the row is being
   * written is held through rather than answered as a 500, which is the difference between one
   * pause here and a retry storm across every publisher. The retried body is the lookup and the
   * insert; validation and the parent check stay outside it, where a 400 costs no transaction.
   */
  public Published publish(
      String id,
      String name,
      Instant occurredAt,
      String payload,
      String description,
      String parentId,
      String environment) {
    Validations.requireUuid(id, "id");
    Validations.requireText(name, "name");
    // Unlike create, publish will not default it: an event whose time this server invented could
    // never be replayed equal to itself, so the one field that makes idempotency decidable is
    // required rather than filled in.
    Validations.requirePresent(occurredAt, "occurredAt");
    Instant when = atStoredPrecision(occurredAt);
    String cause = causeOf(id, parentId);
    String tier = environmentOf(environment);
    return DbRetry.inNewTx(
        "publish an event", () -> decide(id, name, when, payload, description, cause, tier));
  }

  /** One attempt at {@link #publish}: the whole of it, and nothing that is not database work. */
  private Published decide(
      String id,
      String name,
      Instant when,
      String payload,
      String description,
      String cause,
      String environment) {
    Event existing = eventRepository.findById(id);
    if (existing == null) {
      return new Published(
          store(id, name, when, payload, description, cause, environment), PublishOutcome.CREATED);
    }

    if (!name.equals(existing.name)
        || !when.equals(existing.occurredAt)
        || !Objects.equals(payload, existing.payload)
        || !Objects.equals(cause, existing.parentId)
        || !Objects.equals(environment, existing.environment)) {
      throw new BadRequestException(
          "Event " + id + " already exists with different content — a UUID may not be reused");
    }
    return new Published(existing, PublishOutcome.REPLAYED);
  }

  @Transactional
  public void delete(String id) {
    eventRepository.delete(get(id));
  }

  /**
   * The row, written — the one insert both write paths share, and the one place the retried body
   * ends.
   *
   * <p><b>The flush is not decoration.</b> Hibernate defers an insert of an entity with an assigned
   * id to commit time, and a connection that dies during a commit is the one failure {@link
   * DbRetry#inNewTx} cannot classify: the row may be in the database with the answer lost on the way
   * back. Flushing here puts the INSERT on the wire inside the body, where a lost connection is
   * <em>known</em> to have written nothing and a second attempt is safe.
   *
   * <p><b>The announce is a database-only statement in the sense that matters.</b> {@link
   * EventCreated} is observed {@code AFTER_SUCCESS}, so firing it registers a transaction
   * synchronization and nothing more; an attempt that rolls back notifies nobody, and subscribers
   * see exactly one frame per committed row however many attempts it took.
   */
  private Event store(
      String id,
      String name,
      Instant when,
      String payload,
      String description,
      String cause,
      String environment) {
    Event event = new Event();
    event.id = id;
    event.name = name;
    event.occurredAt = when;
    event.payload = payload;
    event.description = description;
    event.parentId = cause;
    event.environment = environment;
    eventRepository.persist(event);
    eventRepository.flush();
    announce(event);
    return event;
  }

  private void announce(Event event) {
    created.fire(
        new EventCreated(
            event.id,
            event.name,
            event.occurredAt,
            event.payload,
            event.description,
            event.parentId,
            event.environment));
  }

  /**
   * The cause to store, or null — the whole of what this service will say about a {@code parentId},
   * and the same two rules on both write paths.
   *
   * <ol>
   *   <li>It must be a canonical UUID when there is one. Same guard as the id itself: a cause is an
   *       id of this table, and a caller that cannot spell one is naming nothing.
   *   <li>It may not be the event's own id. An event cannot cause itself, and that much is decidable
   *       from a single row with no graph to consult — malformed input in the same sense a non-UUID
   *       is, so 400.
   * </ol>
   *
   * <p><b>And there is deliberately no third rule.</b> This is the place an existence check would
   * go, and it is not here: nothing orders a parent's arrival before its child's, so a check would
   * refuse a child whose parent is still in a publisher's outbox — with a 400, which is unretryable,
   * so the outbox marks it FAILED and a timing accident becomes permanent loss. The same argument
   * covers the first retention policy (which will begin invalidating parents of events still in the
   * window) and a parent published by a service this instance never heard from. <b>A dangling parent
   * is data</b>; the reader treats it as the start of the chain.
   *
   * <p>Nor is there a cycle guard. One that caught only the length-one cycle would be worse than
   * none — it cannot see {@code A → B → A}, while its presence tells a reader that cycles have been
   * handled. Detection belongs where the graph is visible, and this column is what makes it
   * possible; a self-edge is refused above because it is validation rather than analysis.
   *
   * <p>Blank normalises to null, so that "no parent" is one value and a replay of it compares equal.
   */
  private static String causeOf(String id, String parentId) {
    Validations.requireUuidIfPresent(parentId, "parentId");
    String cause = parentId == null || parentId.isBlank() ? null : parentId;
    if (cause != null && cause.equals(id)) {
      throw new BadRequestException("Event " + id + " cannot be its own parent");
    }
    return cause;
  }

  /**
   * The environment to store, or null — {@link #causeOf}'s shape for the tier field, and the same
   * rules on both write paths.
   *
   * <p>It must be a dns-safe environment name when there is one ({@code dev}, {@code platform}) —
   * the shape qits-deployments gives environments, and the literal a platform-tier publisher stamps.
   * There is deliberately no check against the environments themselves, for {@code causeOf}'s
   * reason: those rows live in another context's store, an environment can be deleted after its
   * events happened, and a 400 here is unretryable — a timing accident would become permanent loss.
   *
   * <p>Blank normalises to null, so that "no tier" is one value and a replay of it compares equal.
   * Null stays null rather than defaulting: the <em>publisher</em> resolves its tier (that is where
   * the configuration lives), and an event that arrives without one predates the field — inventing
   * a value here would forge history.
   */
  private static String environmentOf(String environment) {
    Validations.requireEnvironmentIfPresent(environment, "environment");
    return environment == null || environment.isBlank() ? null : environment;
  }

  /**
   * The instant as the column will hand it back: {@code timestamp(6)}, so microseconds.
   *
   * <p>Without this the equality in {@link #publish} is decided against two values of different
   * precision — the caller's, and the truncated one the database returns — and a publisher whose
   * clock has nanoseconds would get a 400 on its own honest retry. Truncating on the way in makes
   * what is stored, what is returned and what is compared the same value, which is the only way the
   * word "exactly" in the contract means anything.
   */
  private static Instant atStoredPrecision(Instant instant) {
    return instant.truncatedTo(ChronoUnit.MICROS);
  }
}
