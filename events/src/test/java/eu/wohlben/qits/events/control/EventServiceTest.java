package eu.wohlben.qits.events.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.events.entity.Event;
import eu.wohlben.qits.events.error.BadRequestException;
import eu.wohlben.qits.events.error.NotFoundException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;

@QuarkusTest
class EventServiceTest extends EventsTestSupport {

  @Inject EventService eventService;

  @Test
  void createReadDelete() {
    Instant when = Instant.parse("2026-07-31T09:00:00Z");
    Event event =
        eventService.create("Deployed qits-events", when, "{\"version\":\"1\"}", "First boot", null, null);
    assertNotNull(event.id);
    assertEquals(when, event.occurredAt);
    assertNotNull(event.createdAt);
    assertNotNull(event.updatedAt);
    assertFalse(event.updatedAt.isBefore(event.createdAt));

    Event fetched = eventService.get(event.id);
    assertEquals("Deployed qits-events", fetched.name);
    assertEquals("{\"version\":\"1\"}", fetched.payload);

    eventService.delete(event.id);
    inFreshTx(() -> assertThrows(NotFoundException.class, () -> eventService.get(event.id)));
  }

  @Test
  void aManuallyRecordedEventNeedsNoPayload() {
    // The POST path stays what it was: a name and a time are the whole of what an event must have,
    // and the bus's structured half is optional rather than a new obligation on a person.
    Event event = eventService.create("By hand", Instant.parse("2026-07-31T09:00:00Z"), null, null, null, null);
    assertNull(event.payload);
  }

  @Test
  void anOmittedOccurredAtDefaultsToNow() {
    Instant before = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    Event event = eventService.create("Right now", null, null, null, null, null);
    assertFalse(event.occurredAt.isBefore(before));
  }

  @Test
  void anEventMayBeRecordedInThePast() {
    // The normal case, not an edge one: a log is mostly written after the fact.
    Instant longAgo = Instant.parse("2020-01-01T00:00:00Z");
    Event event = eventService.create("Backfilled", longAgo, null, null, null, null);
    assertEquals(longAgo, event.occurredAt);
    // ... and the row's own timestamps do not follow it, which is the whole reason there are three.
    assertTrue(event.createdAt.isAfter(longAgo));
  }

  @Test
  void listIsNewestFirstByWhenItHappened() {
    // Insertion order deliberately disagrees with occurrence order — that is what is under test.
    eventService.create("Middle", Instant.parse("2026-06-01T00:00:00Z"), null, null, null, null);
    eventService.create("Oldest", Instant.parse("2026-01-01T00:00:00Z"), null, null, null, null);
    eventService.create("Newest", Instant.parse("2026-12-01T00:00:00Z"), null, null, null, null);

    List<String> names = eventService.list().stream().map(e -> e.name).toList();
    assertEquals(List.of("Newest", "Middle", "Oldest"), names);
  }

  @Test
  void aHandRecordedEventMayNameACauseToo() {
    // The manual path takes a parentId and validates it exactly as publish does. A person recording
    // by hand rarely has one, but a field the bus accepts and this path silently dropped would be
    // two definitions of the envelope hiding behind one entity.
    Instant when = Instant.parse("2026-07-31T09:00:00Z");
    Event root = eventService.create("Root", when, null, null, null, null);
    assertNull(root.parentId);

    Event child = eventService.create("Caused", when, null, null, root.id, null);
    assertEquals(root.id, child.parentId);
    assertEquals(
        List.of("Caused"),
        eventService.listChildrenOf(root.id).stream().map(e -> e.name).toList());
  }

  @Test
  void aHandRecordedEventsCauseIsValidatedTheSameWay() {
    Instant when = Instant.parse("2026-07-31T09:00:00Z");
    assertThrows(
        BadRequestException.class, () -> eventService.create("Bad cause", when, null, null, "nope", null));
    // Blank is "no value" rather than an error — a client that meant to say nothing, clumsily — and
    // it normalises to null so that "no parent" is ONE value that replays equal to itself.
    assertNull(eventService.create("Blank cause", when, null, null, "  ", null).parentId);
  }

  @Test
  void tiedEventsAreOrderedByIdSoTheOrderIsTotal() {
    // The rows this matters for are not hypothetical: a pipeline run's events carry the run's finish
    // instant, so a fork's siblings tie to the microsecond by construction. Sorted on occurredAt
    // alone their order is the database's choice, and two identical requests may disagree — which
    // makes rendering unstable before it makes paging lossy.
    Instant tie = Instant.parse("2026-08-01T08:52:23.928965Z");
    eventService.create("Sibling", tie, null, null, null, null);
    eventService.create("Sibling", tie, null, null, null, null);
    eventService.create("Sibling", tie, null, null, null, null);

    List<String> ids = eventService.list().stream().map(e -> e.id).toList();
    List<String> descending = ids.stream().sorted(java.util.Comparator.reverseOrder()).toList();
    assertEquals(descending, ids);
    // ... and it is the same answer twice, which is the property the sort exists for.
    assertEquals(ids, eventService.list().stream().map(e -> e.id).toList());
  }

  @Test
  void aCursorWalksTheWholeLogAndTheLastPageSaysSo() {
    for (int i = 1; i <= 5; i++) {
      eventService.create(
          "Row " + i, Instant.parse("2026-0" + i + "-01T00:00:00Z"), null, null, null, null);
    }

    List<String> walked = new java.util.ArrayList<>();
    EventCursor cursor = null;
    EventService.EventPage page;
    do {
      String from = cursor == null ? null : cursor.format();
      page = eventService.list(EventQuery.of(null, null, null, from, "2"));
      page.events().forEach(e -> walked.add(e.name));
      cursor = page.nextCursor();
    } while (cursor != null);

    assertEquals(List.of("Row 5", "Row 4", "Row 3", "Row 2", "Row 1"), walked);
    // Five rows in pages of two: the walk ends on a page of one, and a page that is not full never
    // offers a cursor.
    assertEquals(1, page.events().size());
    assertNull(page.nextCursor());
  }

  @Test
  void aPageBoundaryOnATieSplitsItWithoutDroppingOrRepeatingASibling() {
    // The case a scalar `before=<occurredAt>` cursor gets wrong: the boundary falls INSIDE a tie.
    // `before` would resume at the instant and either skip the second sibling (strictly older) or
    // hand back the first one again (older or equal). The composite cursor resumes after a ROW.
    Instant tie = Instant.parse("2026-08-01T08:52:23.928965Z");
    Event newest =
        eventService.create("Newest", Instant.parse("2026-08-01T09:00:00Z"), null, null, null, null);
    Event forkA = eventService.create("Fork", tie, null, null, null, null);
    Event forkB = eventService.create("Fork", tie, null, null, null, null);
    Event oldest =
        eventService.create("Oldest", Instant.parse("2026-08-01T08:00:00Z"), null, null, null, null);

    EventService.EventPage first = eventService.list(EventQuery.of(null, null, null, null, "2"));
    assertEquals(2, first.events().size());
    assertNotNull(first.nextCursor());
    assertEquals(newest.id, first.events().get(0).id);
    // The second row is one of the two siblings — whichever has the larger id — and the cursor
    // carries that id beside the shared instant.
    String siblingOnPageOne = first.events().get(1).id;
    assertTrue(siblingOnPageOne.equals(forkA.id) || siblingOnPageOne.equals(forkB.id));
    assertEquals(tie, first.nextCursor().occurredAt());
    assertEquals(siblingOnPageOne, first.nextCursor().id());

    EventService.EventPage second =
        eventService.list(EventQuery.of(null, null, null, first.nextCursor().format(), "2"));
    List<String> both = new java.util.ArrayList<>();
    first.events().forEach(e -> both.add(e.id));
    second.events().forEach(e -> both.add(e.id));

    assertEquals(4, both.size(), "four rows, each exactly once, across the tie: " + both);
    assertEquals(4, java.util.Set.copyOf(both).size(), "no row may appear twice: " + both);
    assertTrue(both.contains(forkA.id) && both.contains(forkB.id), "neither sibling may be dropped");
    assertTrue(both.contains(oldest.id));
    assertNull(second.nextCursor());
  }

  @Test
  void ascendingWalksTheWholeLogOldestFirstAndTheLastPageSaysSo() {
    // The catch-up direction: a durable consumer resumes from the last row it handled and reads
    // FORWARD to the head. The same walk as the descending one above, turned round — same cursor
    // value, same end-of-log signal, opposite order.
    for (int i = 1; i <= 5; i++) {
      eventService.create(
          "Row " + i, Instant.parse("2026-0" + i + "-01T00:00:00Z"), null, null, null, null);
    }

    List<String> walked = new java.util.ArrayList<>();
    EventCursor cursor = null;
    EventService.EventPage page;
    do {
      String from = cursor == null ? null : cursor.format();
      page = eventService.list(EventQuery.of(null, null, null, from, "2", List.of(), "asc"));
      page.events().forEach(e -> walked.add(e.name));
      cursor = page.nextCursor();
    } while (cursor != null);

    assertEquals(List.of("Row 1", "Row 2", "Row 3", "Row 4", "Row 5"), walked);
    assertEquals(1, page.events().size());
    assertNull(page.nextCursor());
  }

  @Test
  void ascendingResumesStrictlyAfterTheWatermarkRowItWasHandedBack() {
    // What a consumer's watermark actually is: the cursor of the page it finished. Sent back
    // verbatim it must yield the NEXT row, never the one it names again — a repeat would be handled
    // twice by anything without its own dedupe, and a skip would be a lost event.
    for (int i = 1; i <= 3; i++) {
      eventService.create(
          "Row " + i, Instant.parse("2026-0" + i + "-01T00:00:00Z"), null, null, null, null);
    }

    EventService.EventPage first =
        eventService.list(EventQuery.of(null, null, null, null, "1", List.of(), "asc"));
    assertEquals(List.of("Row 1"), names(first));
    EventCursor watermark = first.nextCursor();
    assertNotNull(watermark);
    assertEquals(first.events().get(0).id, watermark.id());
    assertEquals(first.events().get(0).occurredAt, watermark.occurredAt());

    // The same watermark twice gives the same answer: nothing here is consumed by reading it.
    String from = watermark.format();
    assertEquals(
        List.of("Row 2"),
        names(eventService.list(EventQuery.of(null, null, null, from, "1", List.of(), "asc"))));
    assertEquals(
        List.of("Row 2", "Row 3"),
        names(eventService.list(EventQuery.of(null, null, null, from, "9", List.of(), "asc"))));
  }

  @Test
  void anAscendingPageBoundaryOnATieSplitsItWithoutDroppingOrRepeatingASibling() {
    // The whole reason the cursor is composite, in the direction catch-up reads: the boundary falls
    // INSIDE a fork, whose siblings share the run's finish instant to the microsecond. A scalar
    // `after=<occurredAt>` would skip the second sibling (strictly newer) or repeat the first
    // (newer or equal). The flipped composite predicate resumes after a ROW.
    Instant tie = Instant.parse("2026-08-01T08:52:23.928965Z");
    Event oldest =
        eventService.create("Oldest", Instant.parse("2026-08-01T08:00:00Z"), null, null, null, null);
    Event forkA = eventService.create("Fork", tie, null, null, null, null);
    Event forkB = eventService.create("Fork", tie, null, null, null, null);
    Event newest =
        eventService.create("Newest", Instant.parse("2026-08-01T09:00:00Z"), null, null, null, null);

    EventService.EventPage first =
        eventService.list(EventQuery.of(null, null, null, null, "2", List.of(), "asc"));
    assertEquals(2, first.events().size());
    assertEquals(oldest.id, first.events().get(0).id);
    // The second row is one of the two siblings — whichever has the smaller id, since the id half of
    // the sort turns round with the instant half — and the cursor carries it beside the shared
    // instant.
    String siblingOnPageOne = first.events().get(1).id;
    assertTrue(siblingOnPageOne.equals(forkA.id) || siblingOnPageOne.equals(forkB.id));
    assertNotNull(first.nextCursor());
    assertEquals(tie, first.nextCursor().occurredAt());
    assertEquals(siblingOnPageOne, first.nextCursor().id());

    EventService.EventPage second =
        eventService.list(
            EventQuery.of(null, null, null, first.nextCursor().format(), "2", List.of(), "asc"));
    List<String> both = new java.util.ArrayList<>();
    first.events().forEach(e -> both.add(e.id));
    second.events().forEach(e -> both.add(e.id));

    assertEquals(4, both.size(), "four rows, each exactly once, across the tie: " + both);
    assertEquals(4, java.util.Set.copyOf(both).size(), "no row may appear twice: " + both);
    assertTrue(both.contains(forkA.id) && both.contains(forkB.id), "neither sibling may be dropped");
    assertTrue(both.contains(newest.id));
    assertNull(second.nextCursor());
  }

  @Test
  void ascendingIsTheDescendingReadingReversedAndNothingElse() {
    // One log, two directions, the same rows: the property that makes a catch-up consumer and the
    // SPA agree about what history is.
    Instant tie = Instant.parse("2026-08-01T08:52:23.928965Z");
    Event forkA = eventService.create("Fork", tie, null, null, null, null);
    Event forkB = eventService.create("Fork", tie, null, null, null, null);
    eventService.create("Newest", Instant.parse("2026-08-01T09:00:00Z"), null, null, null, null);
    eventService.create("Oldest", Instant.parse("2026-08-01T08:00:00Z"), null, null, null, null);

    List<String> newestFirst =
        ids(eventService.list(EventQuery.of(null, null, null, null, null, List.of(), "desc")));
    List<String> oldestFirst =
        ids(eventService.list(EventQuery.of(null, null, null, null, null, List.of(), "asc")));

    assertEquals(newestFirst.reversed(), oldestFirst);
    // ... including the tied pair, whose order the id decides in both directions: the sort's id half
    // turns round with its instant half, so the smaller id comes first ascending. Without that the
    // reversal above would hold only by luck.
    List<String> siblingsAscending =
        oldestFirst.stream().filter(id -> id.equals(forkA.id) || id.equals(forkB.id)).toList();
    assertEquals(siblingsAscending.stream().sorted().toList(), siblingsAscending);
  }

  @Test
  void ascendingComposesWithTheNameAndSinceFilters() {
    // Catch-up is a filtered read: a consumer subscribes to a handful of names and starts from a
    // watermark, so the direction has to compose with every filter rather than replace them.
    eventService.create("BuildSuccessful", Instant.parse("2026-08-01T09:00:00Z"), null, null, null, null);
    eventService.create("SCMRelease", Instant.parse("2026-08-01T09:00:01Z"), null, null, null, null);
    eventService.create("BuildSuccessful", Instant.parse("2026-08-01T09:00:02Z"), null, null, null, null);
    eventService.create("BuildSuccessful", Instant.parse("2026-07-01T09:00:00Z"), null, null, null, null);

    assertEquals(
        3,
        eventService
            .list(EventQuery.of("BuildSuccessful", null, null, null, null, List.of(), "asc"))
            .events()
            .size());
    // since is still an inclusive lower bound, which in this direction is where the page starts.
    EventService.EventPage page =
        eventService.list(
            EventQuery.of(
                "BuildSuccessful", "2026-08-01T09:00:00Z", null, null, null, List.of(), "asc"));
    assertEquals(List.of("BuildSuccessful", "BuildSuccessful"), names(page));
    assertEquals(Instant.parse("2026-08-01T09:00:00Z"), page.events().get(0).occurredAt);
    assertEquals(Instant.parse("2026-08-01T09:00:02Z"), page.events().get(1).occurredAt);
  }

  @Test
  void anUnknownOrderIsAFourHundredAndAbsentIsNewestFirst() {
    // order is a parameter this service defined, so a misspelling is a client error worth naming —
    // and answering it with the OPPOSITE direction would hand a catch-up consumer the head of the
    // log and let it record a watermark it never reached.
    assertThrows(
        BadRequestException.class,
        () -> EventQuery.of(null, null, null, null, null, List.of(), "sideways"));
    assertEquals(EventOrder.DESC, EventOrder.parse(null));
    assertEquals(EventOrder.DESC, EventOrder.parse("  "));
    assertEquals(EventOrder.DESC, EventOrder.parse("desc"));
    // Case is not part of the vocabulary: the two spellings could not mean different things.
    assertEquals(EventOrder.ASC, EventOrder.parse("ASC"));
    assertEquals(EventOrder.ASC, EventOrder.parse(" asc "));
    // Absent is byte-for-byte what the route always answered.
    assertEquals(
        EventQuery.of(null, null, null, null, null),
        EventQuery.of(null, null, null, null, null, List.of(), "desc"));
  }

  @Test
  void theNameFilterTakesOneNameOrACommaSeparatedSet() {
    // The same vocabulary the stream's subscribe frame uses, so a filter means one thing live and
    // one thing historically.
    //
    // Three DISTINCT instants, because this case is about which rows come back and not about how a
    // tie is broken. Written first with one shared instant, it asserted an order that fell through
    // to the id — random UUIDs — and passed twice before failing: the tiebreaker is total, which is
    // the point of it, but it is not the caller's to predict.
    eventService.create("BuildSuccessful", Instant.parse("2026-08-01T09:00:00Z"), null, null, null, null);
    eventService.create("SCMRelease", Instant.parse("2026-08-01T09:00:01Z"), null, null, null, null);
    eventService.create("SoftwareRelease", Instant.parse("2026-08-01T09:00:02Z"), null, null, null, null);

    assertEquals(
        List.of("SCMRelease"),
        names(eventService.list(EventQuery.of("SCMRelease", null, null, null, null))));
    assertEquals(
        List.of("SoftwareRelease", "SCMRelease"),
        names(
            eventService.list(
                EventQuery.of("SCMRelease, SoftwareRelease", null, null, null, null))));
    assertEquals(
        List.of(), names(eventService.list(EventQuery.of("NoSuchName", null, null, null, null))));
    // Blank is absent, the rule ?parentId= already follows.
    assertEquals(3, eventService.list(EventQuery.of("  ", null, null, null, null)).events().size());
  }

  @Test
  void sinceIsAnInclusiveLowerBoundAndThereIsNoUpperOne() {
    eventService.create("Old", Instant.parse("2026-07-31T23:59:59Z"), null, null, null, null);
    eventService.create("Boundary", Instant.parse("2026-08-01T00:00:00Z"), null, null, null, null);
    eventService.create("New", Instant.parse("2026-08-01T00:00:01Z"), null, null, null, null);

    assertEquals(
        List.of("New", "Boundary"),
        names(eventService.list(EventQuery.of(null, "2026-08-01T00:00:00Z", null, null, null))));
  }

  @Test
  void qIsASubstringOfTheOpaquePayload() {
    Instant when = Instant.parse("2026-08-01T09:00:00Z");
    eventService.create("Build", when, "{\"repoId\":\"qits-stt\"}", null, null, null);
    eventService.create("Release", when, "{\"repository\":\"qits-stt\"}", null, null, null);
    eventService.create("Package", when, "{\"packageName\":\"qits/qits-stt\"}", null, null, null);
    eventService.create("Elsewhere", when, "{\"repoId\":\"qits-events\"}", null, null, null);
    eventService.create("Payloadless", when, null, null, null, null);

    // One question, three keys, and no single key that means "which repository" — which is why this
    // searches the string rather than pretending to filter a field.
    assertEquals(
        3, eventService.list(EventQuery.of(null, null, "qits-stt", null, null)).events().size());
    // Case-insensitive, and a payload-less row simply does not match.
    assertEquals(
        3, eventService.list(EventQuery.of(null, null, "QITS-STT", null, null)).events().size());
    assertEquals(
        List.of(), names(eventService.list(EventQuery.of(null, null, "nothing-here", null, null))));
    // A wildcard the caller typed is a character, not a wildcard: "substring" means substring.
    assertEquals(
        List.of(), names(eventService.list(EventQuery.of(null, null, "qits%stt", null, null))));
  }

  @Test
  void attrFilterMatchesExactFragmentAndAndsAcrossFilters() {
    Instant when = Instant.parse("2026-08-01T09:00:00Z");
    eventService.create(
        "Daemon", when, "{\"packageName\":\"qits-ci-daemon\",\"packageType\":\"daemon\"}", null, null, null);
    eventService.create(
        "Docker", when, "{\"packageName\":\"qits-ci\",\"packageType\":\"docker\"}", null, null, null);

    assertEquals(
        List.of("Daemon"),
        names(
            eventService.list(
                EventQuery.of(null, null, null, null, null, List.of("packageType=daemon")))));
    // "dae" is a substring of "daemon", but the closing quote in the built literal keeps a shorter
    // value from matching a longer one — the property that makes this an exact match rather than q
    // wearing a key name.
    assertEquals(
        List.of(),
        names(
            eventService.list(
                EventQuery.of(null, null, null, null, null, List.of("packageType=dae")))));
    // Two filters AND rather than OR.
    assertEquals(
        List.of("Daemon"),
        names(
            eventService.list(
                EventQuery.of(
                    null,
                    null,
                    null,
                    null,
                    null,
                    List.of("packageType=daemon", "packageName=qits-ci-daemon")))));
  }

  @Test
  void attrFilterBlankIsAbsentAndMalformedIsRejected() {
    // The rule ?parentId= already follows: a filter said clumsily is no filter at all.
    assertEquals(
        EventQuery.of(null, null, null, null, null),
        EventQuery.of(null, null, null, null, null, List.of("", "   ")));
    assertThrows(
        BadRequestException.class,
        () -> EventQuery.of(null, null, null, null, null, List.of("no-equals-sign")));
  }

  @Test
  void environmentFilterIsAnExactMatchOnTheStampedTier() {
    Instant when = Instant.parse("2026-08-01T09:00:00Z");
    eventService.create("InDev", when, null, null, null, "dev");
    eventService.create("OnPlatform", when, null, null, null, "platform");
    eventService.create("BeforeTiers", when, null, null, null, null);

    assertEquals(
        List.of("InDev"),
        names(eventService.list(EventQuery.of(null, null, null, null, null, null, null, "dev"))));
    // A tier nothing was published from is an empty page, not an error — the filter is exact and
    // the null of a pre-tier event matches no value.
    assertEquals(
        List.of(),
        names(eventService.list(EventQuery.of(null, null, null, null, null, null, null, "prod"))));
    // Blank is absent, the rule every filter here follows.
    assertEquals(
        3,
        eventService
            .list(EventQuery.of(null, null, null, null, null, null, null, "  "))
            .events()
            .size());
    // ... and a value that could never have been stored is a 400 naming the parameter, the same
    // shape guard the write path applies.
    assertThrows(
        BadRequestException.class,
        () -> EventQuery.of(null, null, null, null, null, null, null, "Not A Slug"));
  }

  @Test
  void tooManyAttrFiltersIsRejected() {
    List<String> tooMany =
        java.util.stream.IntStream.rangeClosed(0, EventQuery.MAX_ATTR_FILTERS)
            .mapToObj(i -> "k" + i + "=v" + i)
            .toList();
    assertThrows(
        BadRequestException.class, () -> EventQuery.of(null, null, null, null, null, tooMany));
  }

  @Test
  void theVocabularyIsEachNameOnceAlphabetically() {
    Instant when = Instant.parse("2026-08-01T09:00:00Z");
    eventService.create("SoftwareRelease", when, null, null, null, null);
    eventService.create("BuildSuccessful", when, null, null, null, null);
    eventService.create("BuildSuccessful", when, null, null, null, null);
    eventService.create("SCMRelease", when, null, null, null, null);

    assertEquals(List.of("BuildSuccessful", "SCMRelease", "SoftwareRelease"), eventService.names());
  }

  @Test
  void theLimitDefaultsClampsAndRefusesWhatIsNotAPageSize() {
    assertEquals(EventQuery.DEFAULT_LIMIT, EventQuery.of(null, null, null, null, null).limit());
    assertEquals(EventQuery.DEFAULT_LIMIT, EventQuery.of(null, null, null, null, "  ").limit());
    assertEquals(10, EventQuery.of(null, null, null, null, "10").limit());
    // The clamp is silent-safe: the response says what it returned and whether more exist, so a
    // client asking loosely for 5,000 rows is answered rather than corrected.
    assertEquals(EventQuery.MAX_LIMIT, EventQuery.of(null, null, null, null, "5000").limit());

    assertThrows(
        BadRequestException.class, () -> EventQuery.of(null, null, null, null, "0"));
    assertThrows(
        BadRequestException.class, () -> EventQuery.of(null, null, null, null, "-1"));
    assertThrows(
        BadRequestException.class, () -> EventQuery.of(null, null, null, null, "lots"));
  }

  @Test
  void anUnreadableCursorOrSinceIsAFourHundredRatherThanAnIgnoredFilter() {
    // A cursor is a value this service handed out, so one it cannot read is a client error worth
    // saying out loud — silently returning the head of the log would look like the end of history.
    assertThrows(BadRequestException.class, () -> EventQuery.of(null, null, null, "no-comma", null));
    assertThrows(
        BadRequestException.class, () -> EventQuery.of(null, null, null, "yesterday,some-id", null));
    assertThrows(
        BadRequestException.class,
        () -> EventQuery.of(null, null, null, "2026-08-01T00:00:00Z,   ", null));
    assertThrows(
        BadRequestException.class, () -> EventQuery.of(null, "yesterday", null, null, null));
  }

  @Test
  void aCursorRoundTripsThroughItsText() {
    EventCursor cursor =
        new EventCursor(
            Instant.parse("2026-08-01T08:52:23.928965Z"), "0bdbe98d-1111-2222-3333-444455556666");
    assertEquals(cursor, EventCursor.parse(cursor.format()));
    assertEquals(
        "2026-08-01T08:52:23.928965Z,0bdbe98d-1111-2222-3333-444455556666", cursor.format());
    assertNull(EventCursor.parse(null));
    assertNull(EventCursor.parse("  "));
  }

  private static List<String> names(EventService.EventPage page) {
    return page.events().stream().map(e -> e.name).toList();
  }

  private static List<String> ids(EventService.EventPage page) {
    return page.events().stream().map(e -> e.id).toList();
  }

  @Test
  void blankNameIsRejected() {
    assertThrows(BadRequestException.class, () -> eventService.create("  ", null, null, null, null, null));
  }

  @Test
  void getUnknownEventThrowsNotFound() {
    assertThrows(NotFoundException.class, () -> eventService.get("nope"));
  }

  @Test
  void deleteUnknownEventThrowsNotFound() {
    assertThrows(NotFoundException.class, () -> eventService.delete("nope"));
  }
}
