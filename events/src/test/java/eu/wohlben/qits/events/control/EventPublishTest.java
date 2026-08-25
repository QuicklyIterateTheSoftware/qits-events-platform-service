package eu.wohlben.qits.events.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.wohlben.qits.events.control.EventService.PublishOutcome;
import eu.wohlben.qits.events.control.EventService.Published;
import eu.wohlben.qits.events.error.BadRequestException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The bus's write path: {@code publish} under the caller's own UUID, which is what makes a
 * publisher's retry safe. The three outcomes are the wire contract, so they are pinned here at the
 * control layer as well as over HTTP — the boundary only translates them into 201/200/400.
 */
@QuarkusTest
class EventPublishTest extends EventsTestSupport {

  private static final String PAYLOAD =
      "{\"branch\":\"main\",\"commitSha\":\"abc123\",\"repoId\":\"qits-ci\"}";

  @Inject EventService eventService;

  private static String aUuid() {
    return UUID.randomUUID().toString();
  }

  @Test
  void anUnknownIdIsCreatedUnderTheCallersOwnUuid() {
    String id = aUuid();
    Instant when = Instant.parse("2026-07-31T12:46:03Z");

    Published published = eventService.publish(id, "BuildSuccessful", when, PAYLOAD, null, null, null);

    assertEquals(PublishOutcome.CREATED, published.outcome());
    // The id is the PUBLISHER's, not one this service invented — that is the whole mechanism.
    assertEquals(id, published.event().id);
    assertEquals(when, published.event().occurredAt);
    assertEquals(PAYLOAD, published.event().payload);
  }

  @Test
  void theSameEventArrivingTwiceIsAReplayAndWritesNothing() {
    String id = aUuid();
    Instant when = Instant.parse("2026-07-31T12:46:03Z");
    eventService.publish(id, "BuildSuccessful", when, PAYLOAD, "first attempt", null, null);

    Published again = eventService.publish(id, "BuildSuccessful", when, PAYLOAD, "first attempt", null, null);

    assertEquals(PublishOutcome.REPLAYED, again.outcome());
    assertEquals(1, eventService.list().size(), "a replay must not land a second row");
  }

  @Test
  void aDifferentDescriptionIsStillTheSameEvent() {
    // description is the human account and deliberately outside the comparison: a publisher that
    // improved its wording between attempts has not published a different event. Nothing is
    // written, so the stored account stays the one that was committed.
    String id = aUuid();
    Instant when = Instant.parse("2026-07-31T12:46:03Z");
    eventService.publish(id, "BuildSuccessful", when, PAYLOAD, "as first sent", null, null);

    Published again = eventService.publish(id, "BuildSuccessful", when, PAYLOAD, "reworded", null, null);

    assertEquals(PublishOutcome.REPLAYED, again.outcome());
    inFreshTx(() -> assertEquals("as first sent", eventService.get(id).description));
  }

  @Test
  void aReplayAtFinerClockPrecisionIsStillAReplay() {
    // The trap this exists to hold shut: occurred_at is timestamp(6), so what a nanosecond-precision
    // clock sends is not what the column hands back. Compared naively, a publisher's own honest
    // retry would be told 400 — "you reused a UUID" — for a difference no storage here can even
    // represent.
    String id = aUuid();
    Instant nanos = Instant.parse("2026-07-31T12:46:03Z").plusNanos(123_456_789L);
    eventService.publish(id, "BuildSuccessful", nanos, PAYLOAD, null, null, null);

    Published again = eventService.publish(id, "BuildSuccessful", nanos, PAYLOAD, null, null, null);

    assertEquals(PublishOutcome.REPLAYED, again.outcome());
    inFreshTx(
        () -> assertEquals(eventService.get(id).occurredAt, again.event().occurredAt,
            "what was stored, what is returned and what is compared must be one value"));
  }

  @Test
  void aReusedUuidIsUnretryableRatherThanAConflictToPollOn() {
    String id = aUuid();
    Instant when = Instant.parse("2026-07-31T12:46:03Z");
    eventService.publish(id, "BuildSuccessful", when, PAYLOAD, null, null, null);

    for (Runnable differing :
        List.<Runnable>of(
            () -> eventService.publish(id, "SomethingElse", when, PAYLOAD, null, null, null),
            () ->
                eventService.publish(
                    id, "BuildSuccessful", when.plusSeconds(1), PAYLOAD, null, null, null),
            () ->
                eventService.publish(
                    id, "BuildSuccessful", when, "{\"branch\":\"other\"}", null, null, null),
            () -> eventService.publish(id, "BuildSuccessful", when, null, null, null, null),
            // ... and the fourth field: two PUTs of one id claiming different causes are two
            // different claims about history, in BOTH directions.
            () -> eventService.publish(id, "BuildSuccessful", when, PAYLOAD, null, aUuid(), null),
            // ... and the fifth: one id claiming two tiers is the same disagreement about history,
            // wearing the environment field.
            () -> eventService.publish(id, "BuildSuccessful", when, PAYLOAD, null, null, "dev"))) {
      assertThrows(BadRequestException.class, differing::run);
    }
  }

  @Test
  void anIdThatIsNotAUuidIsRefusedBeforeAnythingIsStored() {
    Instant when = Instant.parse("2026-07-31T12:46:03Z");
    assertThrows(
        BadRequestException.class,
        () -> eventService.publish("not-a-uuid", "BuildSuccessful", when, PAYLOAD, null, null, null));
    // UUID.fromString alone accepts this; the round-trip check is what does not.
    assertThrows(
        BadRequestException.class,
        () -> eventService.publish("1-1-1-1-1", "BuildSuccessful", when, PAYLOAD, null, null, null));
    assertEquals(List.of(), eventService.list());
  }

  @Test
  void anOmittedOccurredAtIsRefusedRatherThanFilledIn() {
    // create() defaults it to now; publish() must not, because an event whose time this server
    // invented could never replay equal to itself.
    assertThrows(
        BadRequestException.class,
        () -> eventService.publish(aUuid(), "BuildSuccessful", null, PAYLOAD, null, null, null));
  }

  @Test
  void aPublishedEventNeedsNoPayloadEither() {
    String id = aUuid();
    Instant when = Instant.parse("2026-07-31T12:46:03Z");
    Published published = eventService.publish(id, "SomethingHappened", when, null, null, null, null);
    assertEquals(PublishOutcome.CREATED, published.outcome());
    assertNull(published.event().payload);

    // ... and null must compare equal to null on the replay, not fall into the mismatch branch.
    assertEquals(
        PublishOutcome.REPLAYED,
        eventService.publish(id, "SomethingHappened", when, null, null, null, null).outcome());
  }

  @Test
  void aParentThisLogHasNeverSeenIsStoredAsItStands() {
    // THE decision this feature rests on. Nothing orders a parent's arrival before its child's:
    // publishes are independent HTTP calls, and a parent whose inline attempt failed sits in the
    // publisher's outbox for minutes while its child lands on the first try. An existence check
    // would 400 that child — unretryable, so the outbox marks it FAILED — and a timing accident
    // would become permanent data loss. A dangling parent is data; the reader treats it as a root.
    String id = aUuid();
    String neverSeen = aUuid();
    Instant when = Instant.parse("2026-07-31T12:46:03Z");

    Published published =
        eventService.publish(id, "BuildSuccessful", when, PAYLOAD, null, neverSeen, null);

    assertEquals(PublishOutcome.CREATED, published.outcome());
    assertEquals(neverSeen, published.event().parentId);
    inFreshTx(() -> assertEquals(neverSeen, eventService.get(id).parentId));
  }

  @Test
  void theEnvironmentIsStoredAndAReplayUnderItComparesEqual() {
    String id = aUuid();
    Instant when = Instant.parse("2026-07-31T12:46:03Z");

    Published published =
        eventService.publish(id, "BuildSuccessful", when, PAYLOAD, null, null, "dev");

    assertEquals(PublishOutcome.CREATED, published.outcome());
    assertEquals("dev", published.event().environment);
    assertEquals(
        PublishOutcome.REPLAYED,
        eventService.publish(id, "BuildSuccessful", when, PAYLOAD, null, null, "dev").outcome());
  }

  @Test
  void aBlankEnvironmentNormalisesToNullSoAReplayOfNoTierComparesEqual() {
    String id = aUuid();
    Instant when = Instant.parse("2026-07-31T12:46:03Z");
    eventService.publish(id, "BuildSuccessful", when, PAYLOAD, null, null, "  ");

    inFreshTx(() -> assertNull(eventService.get(id).environment));
    assertEquals(
        PublishOutcome.REPLAYED,
        eventService.publish(id, "BuildSuccessful", when, PAYLOAD, null, null, null).outcome());
  }

  @Test
  void anEnvironmentThatIsNotADnsSafeNameIsRefusedBeforeAnythingIsStored() {
    // The shape guard, not an existence check: whether 'dev' exists is deliberately never asked —
    // an environment can be deleted after its events happened — but 'Dev', a slash or a 64-char
    // name could never have been an environment at all.
    Instant when = Instant.parse("2026-07-31T12:46:03Z");
    for (String bad : List.of("Dev", "dev/2", "-dev", "dev-", "d".repeat(64))) {
      assertThrows(
          BadRequestException.class,
          () -> eventService.publish(aUuid(), "BuildSuccessful", when, PAYLOAD, null, null, bad));
    }
    assertEquals(List.of(), eventService.list());
  }

  @Test
  void theSameEventUnderTheSameParentIsStillAReplay() {
    String parent = aUuid();
    String id = aUuid();
    Instant when = Instant.parse("2026-07-31T12:46:03Z");
    eventService.publish(id, "BuildSuccessful", when, PAYLOAD, null, parent, null);

    assertEquals(
        PublishOutcome.REPLAYED,
        eventService.publish(id, "BuildSuccessful", when, PAYLOAD, null, parent, null).outcome());
  }

  @Test
  void aParentThatIsNotAUuidIsRefused() {
    // Same guard as the id itself: a cause is an id of this table, and a caller that cannot spell
    // one is naming nothing.
    Instant when = Instant.parse("2026-07-31T12:46:03Z");
    assertThrows(
        BadRequestException.class,
        () -> eventService.publish(aUuid(), "BuildSuccessful", when, PAYLOAD, null, "not-a-uuid", null));
    assertThrows(
        BadRequestException.class,
        () -> eventService.publish(aUuid(), "BuildSuccessful", when, PAYLOAD, null, "1-1-1-1-1", null));
    assertEquals(List.of(), eventService.list());
  }

  @Test
  void anEventMayNotCauseItself() {
    // Decidable from one row with no graph to consult, so it is validation rather than analysis —
    // which is exactly why the length-two cycle is NOT refused here. A guard that caught only the
    // self-edge and let A → B → A through would tell a reader that cycles have been handled.
    String id = aUuid();
    Instant when = Instant.parse("2026-07-31T12:46:03Z");
    assertThrows(
        BadRequestException.class,
        () -> eventService.publish(id, "BuildSuccessful", when, PAYLOAD, null, id, null));
    assertEquals(List.of(), eventService.list());
  }

  @Test
  void theChildrenOfAnEventAreListedNewestFirstAndNobodyElsesAre() {
    String parent = aUuid();
    String other = aUuid();
    eventService.publish(
        aUuid(), "Middle", Instant.parse("2026-06-01T00:00:00Z"), null, null, parent, null);
    eventService.publish(
        aUuid(), "Oldest", Instant.parse("2026-01-01T00:00:00Z"), null, null, parent, null);
    eventService.publish(
        aUuid(), "Newest", Instant.parse("2026-12-01T00:00:00Z"), null, null, parent, null);
    eventService.publish(
        aUuid(), "SomebodyElses", Instant.parse("2026-12-02T00:00:00Z"), null, null, other, null);
    eventService.publish(aUuid(), "ARoot", Instant.parse("2026-12-03T00:00:00Z"), null, null, null, null);

    assertEquals(
        List.of("Newest", "Middle", "Oldest"),
        eventService.listChildrenOf(parent).stream().map(e -> e.name).toList());
    // An id nothing names is an empty list, never an absence: this log cannot tell "wrong" from
    // "not here yet", and "nothing was caused by it as far as I know" is true either way.
    assertEquals(List.of(), eventService.listChildrenOf(aUuid()));
  }
}
