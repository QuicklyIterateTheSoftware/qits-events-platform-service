package eu.wohlben.qits.events.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.wohlben.qits.events.control.EventService.PublishOutcome;
import eu.wohlben.qits.events.entity.Event;
import eu.wohlben.qits.events.persistence.EventRepository;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import org.hibernate.exception.JDBCConnectionException;
import org.junit.jupiter.api.Test;

/**
 * The write path holds through a connection that dies mid-statement, and lands the row exactly once.
 *
 * <p>The stand-in is a repository whose {@code flush()} does the real work and <b>then</b> throws —
 * the statement is on the wire, the transaction is doomed, and nothing is committed. That is the
 * only failure position {@code DbRetry.inNewTx} retries, and reproducing it here rather than killing
 * a socket keeps this module's suite what it is: no docker, no proxy, no second process. The wire
 * proof for the same classification lives in qits-db-core's own {@code DbRetryInNewTxTest}.
 *
 * <p>Both cases below matter, and they are the pair: patience is worth having only if it is narrow.
 */
@QuarkusTest
class EventWritePatienceTest extends EventsTestSupport {

  private static final String PAYLOAD = "{\"repoId\":\"qits-ci\"}";

  @Inject EventService eventService;

  /**
   * The repository with failures planted in {@code flush()}, one per queued exception. Each is
   * thrown <em>after</em> the real flush, which is what makes the row's absence afterwards a
   * statement about the rollback rather than about the insert never having been attempted.
   */
  private static final class FlakyEventRepository extends EventRepository {

    private final Deque<RuntimeException> planted = new ArrayDeque<>();
    private int flushes;

    void plant(RuntimeException failure) {
      planted.add(failure);
    }

    @Override
    public void flush() {
      super.flush();
      flushes++;
      RuntimeException failure = planted.poll();
      if (failure != null) {
        throw failure;
      }
    }
  }

  private static JDBCConnectionException connectionLost() {
    return new JDBCConnectionException(
        "connection lost", new SQLException("An I/O error occurred", "08006"));
  }

  private FlakyEventRepository flaky() {
    FlakyEventRepository flaky = new FlakyEventRepository();
    QuarkusMock.installMockForType(flaky, EventRepository.class);
    return flaky;
  }

  private long rowsNamed(String name) {
    return eventService.list().stream().filter(event -> name.equals(event.name)).count();
  }

  @Test
  void aConnectionLostMidWriteIsRetriedAndTheRowLandsOnce() {
    FlakyEventRepository flaky = flaky();
    flaky.plant(connectionLost());

    Event recorded = eventService.create("BuildSucceeded", Instant.now(), PAYLOAD, null, null, null);

    assertEquals(2, flaky.flushes, "the first attempt must have reached the database");
    inFreshTx(
        () ->
            assertEquals(
                1L, rowsNamed("BuildSucceeded"), "a retried write must land exactly one row"));
    assertEquals(recorded.id, eventService.get(recorded.id).id);
  }

  @Test
  void theHubWritesRetryToo() {
    FlakyEventRepository flaky = flaky();
    flaky.plant(connectionLost());
    String id = UUID.randomUUID().toString();
    Instant when = Instant.parse("2026-08-11T09:00:00Z");

    var published = eventService.publish(id, "BuildSucceeded", when, PAYLOAD, null, null, null);

    assertEquals(PublishOutcome.CREATED, published.outcome());
    assertEquals(2, flaky.flushes);
    inFreshTx(() -> assertEquals(1L, rowsNamed("BuildSucceeded")));
  }

  @Test
  void aFailureThatIsNotAConnectionIsNotRetried() {
    FlakyEventRepository flaky = flaky();
    flaky.plant(new IllegalStateException("the column refused the value"));

    assertThrows(
        IllegalStateException.class,
        () -> eventService.create("BuildSucceeded", Instant.now(), PAYLOAD, null, null, null));

    assertEquals(1, flaky.flushes, "a second attempt would fail the same way and hide the cause");
    inFreshTx(() -> assertEquals(0L, rowsNamed("BuildSucceeded")));
  }
}
