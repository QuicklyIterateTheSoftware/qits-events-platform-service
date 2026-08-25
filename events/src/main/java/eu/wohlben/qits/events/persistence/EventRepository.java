package eu.wohlben.qits.events.persistence;

import eu.wohlben.qits.events.control.EventOrder;
import eu.wohlben.qits.events.control.EventQuery;
import eu.wohlben.qits.events.entity.Event;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Parameters;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class EventRepository implements PanacheRepositoryBase<Event, String> {

  /**
   * Newest first, by the caller's {@code occurredAt} rather than by insertion order — a backfilled
   * event belongs where it happened, which is the only ordering an event log can be read by — and
   * then <b>by id</b>, descending.
   *
   * <p>The id is not decoration. {@code occurredAt} is not unique and cannot be made unique: the
   * events one pipeline run publishes carry the run's finish instant, so a fork's siblings tie by
   * construction. Sorted on {@code occurredAt} alone the order of a tied pair is whatever the
   * database felt like this time, which makes two identical requests disagree and makes any cursor
   * over the list lossy. Adding the primary key makes the order <b>total</b>, and that is a
   * correctness property this list needs whether or not anyone pages it.
   */
  private static final Sort NEWEST_FIRST =
      Sort.by("occurredAt", Sort.Direction.Descending).and("id", Sort.Direction.Descending);

  /**
   * The same total order read the other way — for a durable consumer catching up from its watermark,
   * which walks forward through history rather than backwards from the head.
   *
   * <p><b>Both halves are reversed, not just the first.</b> Sorting ascending by {@code occurredAt}
   * and descending by id would still be a total order and would still be wrong: the cursor predicate
   * compares the id in the direction the page runs, so a sort that disagreed with it would skip rows
   * inside a tie — the exact failure the composite cursor exists to prevent.
   */
  private static final Sort OLDEST_FIRST =
      Sort.by("occurredAt", Sort.Direction.Ascending).and("id", Sort.Direction.Ascending);

  /**
   * One page of the log, in the order the query asks for, filtered as it says — and <b>one row more
   * than was asked for</b>, so the caller can tell "this is the last page" from "this page happens to
   * be full" without a second query and without a count.
   */
  public List<Event> listPage(EventQuery query) {
    List<String> clauses = new ArrayList<>();
    Parameters parameters = new Parameters();

    if (!query.names().isEmpty()) {
      clauses.add("name in :names");
      parameters.and("names", query.names());
    }
    if (query.since() != null) {
      clauses.add("occurredAt >= :since");
      parameters.and("since", query.since());
    }
    if (query.search() != null) {
      // The payload is text the server treats as opaque, so this is a scan and no index can help
      // it — see V1__init.sql for why that is the honest answer at this table's size.
      clauses.add("lower(payload) like :search escape '!'");
      parameters.and("search", query.search());
    }
    if (query.environment() != null) {
      // An equality on the event's own column — what idx_event_environment exists for — rather
      // than a payload scan: the tier is envelope data, so the filter never touches the payload.
      clauses.add("environment = :environment");
      parameters.and("environment", query.environment());
    }
    for (int i = 0; i < query.attrFilters().size(); i++) {
      // One clause per ?attr=, same scan as ?q= above, narrowed to one "key":"value" fragment —
      // EventQuery.attrFiltersOf already built the escaped pattern, this just binds it.
      String name = "attr" + i;
      clauses.add("lower(payload) like :" + name + " escape '!'");
      parameters.and(name, query.attrFilters().get(i));
    }
    boolean ascending = query.order() == EventOrder.ASC;
    if (query.cursor() != null) {
      // The composite predicate: strictly past the cursor's instant, or the same instant and an id
      // past its id. This is the half a scalar `before=<occurredAt>` cursor cannot express, and the
      // reason a fork's siblings survive a page boundary. Descending reads it as "older, or a
      // smaller id at the same instant"; ascending is the same sentence with both comparisons
      // turned round, so the row the cursor names is excluded either way and nothing beside it is.
      String past = ascending ? ">" : "<";
      clauses.add(
          "(occurredAt " + past + " :cursorAt"
              + " or (occurredAt = :cursorAt and id " + past + " :cursorId))");
      parameters.and("cursorAt", query.cursor().occurredAt()).and("cursorId", query.cursor().id());
    }

    // `1 = 1` is the unfiltered reading. Panache appends the sort to a where clause, so there has to
    // be one to append to.
    String where = clauses.isEmpty() ? "1 = 1" : String.join(" and ", clauses);
    Sort sort = ascending ? OLDEST_FIRST : NEWEST_FIRST;
    return find(where, sort, parameters).page(0, query.limit() + 1).list();
  }

  /**
   * The events caused by one event, newest first — the downward half of a chain walk, and the query
   * {@code idx_event_parent_id} exists for.
   *
   * <p>Unpaged, deliberately: a parent's children are one per artifact a pipeline declares, so the
   * shape is bounded by a file in a repository rather than by history. It takes the same total order
   * as the log, and that matters here more than anywhere — a fork's two children carry the run's
   * finish instant to the microsecond, so without the id they are the one pair of rows a database is
   * free to hand back in a different order each time.
   *
   * <p>An unknown parent yields an empty list rather than an absence: this log does not know whether
   * an id it has never seen is wrong or merely not here yet, and "no children" is the true answer
   * to the question that was asked either way.
   */
  public List<Event> listChildrenOf(String parentId) {
    return list("parentId", NEWEST_FIRST, parentId);
  }

  /**
   * Every name the log holds, once each, in alphabetical order — the vocabulary a filter offers and
   * a subscriber names in its subscribe frame.
   *
   * <p>It is a route of its own because the alternative is fetching all of history to learn five
   * strings, which is the thing paging exists to stop.
   */
  public List<String> distinctNames() {
    return getEntityManager()
        .createQuery("select distinct e.name from Event e order by e.name", String.class)
        .getResultList();
  }
}
