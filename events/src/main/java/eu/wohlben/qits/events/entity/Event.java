package eu.wohlben.qits.events.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A recorded thing that happened.
 *
 * <p>Panache active-record with public fields, the platform's entity idiom. The three timestamps are
 * not redundant: {@code occurredAt} is the caller's — <em>when the thing happened</em>, supplied on
 * write and freely in the past — while {@code createdAt}/{@code updatedAt} are this row's, written
 * by Hibernate. Collapsing them would make a backfilled event indistinguishable from one recorded as
 * it happened, which is the one distinction an event log exists to keep.
 *
 * <p>No relation to any other context's entity, and there will not be one: an event that names a
 * project or a repository names it by String id through this context's own column, because those
 * rows live in another physical database (the platform-wide rule — see AGENTS.md).
 */
@Entity
public class Event extends PanacheEntityBase {

  @Id public String id;

  /**
   * Short label for lists and timelines, and — since this context became the platform's bus — the
   * event's <b>signature</b>: the string a websocket subscriber names to say what it wants. One
   * column serving both is deliberate; a separate signature field would be a second name that could
   * disagree with the first.
   */
  @Column(nullable = false)
  public String name;

  /** When the thing happened, as the caller reports it — not when the row was written. */
  @Column(name = "occurred_at", nullable = false)
  public Instant occurredAt;

  /**
   * The publisher's own fields as canonical JSON — the machine half, where {@link #description} is
   * the human one. Optional, and permanently so: an event recorded by hand through {@code POST} is
   * honestly nothing but a name and a time.
   *
   * <p>Stored and compared <b>verbatim</b>. Canonicalization (sorted keys, no insignificant
   * whitespace, absent fields omitted rather than null) happens in the publisher, and the idempotent
   * {@code PUT} decides "same event or reused UUID?" by comparing this string byte for byte — so a
   * server that reformatted the value would break the one property the retry path rests on.
   */
  public String payload;

  /** The long-form account. Optional: a name and a time are the whole of what an event must have. */
  public String description;

  /**
   * The id of the event that <b>caused</b> this one, or null for a root — the platform's causation
   * edge, and the only relation this table has.
   *
   * <p>Envelope data, not payload: the publisher's canonical JSON is compared byte for byte, so a
   * cause that entered it would make the same event published under two different parents two
   * events this server could not reconcile. It is part of the identity of the occurrence all the
   * same, which is why the idempotent {@code PUT} compares it and leaves {@link #description} out.
   *
   * <p><b>Not validated against this table, deliberately, and there is no FK.</b> Nothing orders a
   * parent's arrival before its child's — an existence check would 400 a child whose parent is
   * still sitting in a publisher's outbox, and 400 is unretryable, so a timing accident would
   * become permanent data loss. A parent id this log cannot resolve is a true statement about
   * causation that this log has not (or no longer has) the other half of; the reader treats it as
   * the start of a chain. See {@code V1__init.sql}.
   */
  @Column(name = "parent_id")
  public String parentId;

  /**
   * The environment tier the publisher ran in — {@code dev}, {@code prod} — or {@code platform} for
   * a publisher that serves every tier, or null for an event recorded before the platform knew
   * tiers. Envelope data like {@link #parentId}: the payload is compared byte for byte, so a tier
   * that entered it would make one occurrence published from two configurations two events.
   *
   * <p>Like the parent, it is part of the identity of the occurrence, so the idempotent {@code PUT}
   * compares it. And like the parent, it names a row of another context's store (qits-deployments'
   * environments table) by value with <b>no FK and no existence check</b>: an environment can be
   * deleted after its events happened, and the events remain true.
   */
  @Column(length = 64)
  public String environment;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  public Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt;
}
