package eu.wohlben.qits.events.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;

/**
 * The envelope of a <em>newly created</em> event: the exact frame {@code /events/stream} pushes, and
 * the in-process signal the control layer fires to get it pushed.
 *
 * <p>The components are the wire contract, so this record's JSON <b>is</b> the frame:
 *
 * <pre>{@code {"id": "<uuid>", "name": "…", "occurredAt": "…", "payload": "…", "description": null,
 * "parentId": null, "environment": null}}</pre>
 *
 * <p><b>Their order is not.</b> It was, while there were five of them; the clause is retired now
 * that there are more, because both sides bind by name and the publishing library disables
 * {@code FAIL_ON_UNKNOWN_PROPERTIES} precisely so a subscriber built against five fields survives a
 * sixth. Which is also the rule for the next one: <b>append</b>, so an old subscriber reads the
 * frame it always read — {@code environment} is the first field added under that rule, and it sits
 * last for exactly that reason.
 *
 * <p>Adding a component here changes what every subscriber receives. The row's own {@code
 * createdAt}/{@code updatedAt} are absent on purpose — they are this database's bookkeeping, not
 * facts about the thing that happened — and {@code id} is present on purpose: it is what a later
 * catch-up protocol will resume from, which is why the live-only stream carries it today.
 *
 * <p>{@code parentId} — the id of the event that caused this one, or null for a root — is here for
 * the same reason {@code id} is: it is a fact about the occurrence, and a subscriber watching the
 * stream draw a release train has no other way to learn the edge. {@code environment} — the tier
 * the publisher ran in, or null when the event predates the field — is on the frame by the same
 * argument: which tier something happened in is a fact about the occurrence, not this database's
 * bookkeeping.
 *
 * <p><b>Fired only on create.</b> An idempotent {@code PUT} that replays an id already stored
 * answers 200 and fires nothing; a subscriber that received the event once must never receive it
 * again because the publisher retried. That is the whole reason this type is named for the
 * transition rather than for the shape — an observer site reading {@code @Observes EventCreated}
 * cannot mistake it for "an event was written in some way".
 *
 * <p>{@code @RegisterForReflection} because this record is serialized by Jackson directly rather
 * than as a JAX-RS return type, so nothing else tells the native-image builder its accessors are
 * reachable. Without it the JVM suite stays green and the binary pushes {@code {}}.
 */
@RegisterForReflection
public record EventCreated(
    String id,
    String name,
    Instant occurredAt,
    String payload,
    String description,
    String parentId,
    String environment) {}
