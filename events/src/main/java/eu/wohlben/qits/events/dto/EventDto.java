package eu.wohlben.qits.events.dto;

import java.time.Instant;

/**
 * A read of one event, as the JSON API returns it. Carries the row's own timestamps, which {@link
 * EventCreated} — the socket frame — deliberately does not: a subscriber is told what happened, not
 * when this database learned of it.
 *
 * <p>{@code parentId} is the id of the event that caused this one, or null for a root. It is what
 * lets a client walk a chain <em>upwards</em> with nothing but the {@code GET /{id}} that already
 * exists; the downward walk — one parent's children — is the {@code ?parentId=} filter on the list
 * route. A {@code parentId} naming an event this log cannot produce is not an error: treat it as the
 * start of the chain. And a chain-walking client must bound its own depth and track the ids it has
 * visited, because nothing on this side prevents a cycle.
 *
 * <p>{@code environment} is the tier the publisher ran in ({@code dev}, {@code platform}), or null
 * for an event recorded before the platform knew tiers. Like {@code parentId} it may name an
 * environment this reader cannot resolve — one deleted since — and that is data, not an error.
 */
public record EventDto(
    String id,
    String name,
    Instant occurredAt,
    String payload,
    String description,
    String parentId,
    String environment,
    Instant createdAt,
    Instant updatedAt) {}
