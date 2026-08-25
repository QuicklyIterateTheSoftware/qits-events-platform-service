package eu.wohlben.qits.events.control;

import eu.wohlben.qits.events.error.BadRequestException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One reading of the log, parsed: which names, how far back, what text to look for, where to resume,
 * which way to run and how many rows to hand back.
 *
 * <p><b>The boundary passes the caller's text through and this record decides what it means.</b>
 * Every parameter arrives as a String — as {@code parentId} always has — rather than as a typed
 * {@code @QueryParam}, so that one place decides what a bad value is and every bad value is a 400
 * whose message names the parameter. A JAX-RS parameter converter would answer for us, in a shape
 * that varies by runtime (the spec says 404 for a query parameter it cannot convert) and with no
 * body worth reading.
 *
 * <p>Blank is absent throughout, the rule {@code ?parentId=} already follows: a client that meant to
 * ask for everything and said it clumsily gets everything.
 */
public record EventQuery(
    List<String> names,
    Instant since,
    String search,
    List<String> attrFilters,
    String environment,
    EventCursor cursor,
    EventOrder order,
    int limit) {

  /**
   * The page a client that asks for no size gets. 200 rows is roughly 90 KB of this log's rows —
   * more than a screen and small enough to be cheap.
   */
  public static final int DEFAULT_LIMIT = 200;

  /**
   * The page no client gets past. The clamp is <b>silent-safe</b> rather than an error: the response
   * says how many rows it carries and whether more exist, so a client never has to infer that it was
   * clamped, and a demand for 5,000 rows is a client asking loosely rather than wrongly.
   */
  public static final int MAX_LIMIT = 1000;

  /**
   * The count no request gets past. Each filter is its own scan of the opaque payload column, the
   * same cost {@code ?q=} pays once — this bounds how many of that scan one request can ask for.
   */
  public static final int MAX_ATTR_FILTERS = 8;

  /** Everything, newest first, at the default page size. */
  public static EventQuery defaults() {
    return of(null, null, null, null, null);
  }

  /**
   * @param name comma-separated event names — the same vocabulary the stream's subscribe frame uses,
   *     which is what makes a filter mean one thing live and historically
   * @param since ISO-8601 lower bound on {@code occurredAt}, inclusive. There is deliberately no
   *     {@code until}: the cursor <em>is</em> the upper bound, and two parameters meaning one thing
   *     are two things to keep in step
   * @param q case-insensitive substring of the payload — see {@link #searchOf}
   * @param cursor the composite {@code <occurredAt>,<id>} of the previous page's last row
   * @param limit page size; absent takes {@link #DEFAULT_LIMIT}, above {@link #MAX_LIMIT} takes the
   *     cap, and nothing else is a page size at all
   */
  public static EventQuery of(String name, String since, String q, String cursor, String limit) {
    return of(name, since, q, cursor, limit, List.of());
  }

  /**
   * @param attr repeatable {@code key=value} filters, each an exact fragment match — see {@link
   *     #attrFiltersOf}. Absent or {@code null} means no attribute filter at all.
   */
  public static EventQuery of(
      String name, String since, String q, String cursor, String limit, List<String> attr) {
    return of(name, since, q, cursor, limit, attr, null);
  }

  /**
   * @param order {@code asc} or {@code desc}; absent is descending, the reading this route has
   *     always answered. Ascending composes with every filter above it and changes nothing else —
   *     see {@link EventOrder}.
   */
  public static EventQuery of(
      String name,
      String since,
      String q,
      String cursor,
      String limit,
      List<String> attr,
      String order) {
    return of(name, since, q, cursor, limit, attr, order, null);
  }

  /**
   * @param environment exact match on the tier the publisher stamped — an equality on its own
   *     column, unlike the payload scans above it. It takes the same shape guard as the write path,
   *     so a value that could never have been stored is a 400 naming the parameter rather than a
   *     silently empty page.
   */
  public static EventQuery of(
      String name,
      String since,
      String q,
      String cursor,
      String limit,
      List<String> attr,
      String order,
      String environment) {
    return new EventQuery(
        namesOf(name),
        sinceOf(since),
        searchOf(q),
        attrFiltersOf(attr),
        environmentOf(environment),
        EventCursor.parse(cursor),
        EventOrder.parse(order),
        limitOf(limit));
  }

  private static String environmentOf(String environment) {
    if (environment == null || environment.isBlank()) {
      return null;
    }
    String trimmed = environment.trim();
    Validations.requireEnvironmentIfPresent(trimmed, "environment");
    return trimmed;
  }

  private static List<String> namesOf(String name) {
    if (name == null || name.isBlank()) {
      return List.of();
    }
    List<String> names = new ArrayList<>();
    for (String each : name.split(",", -1)) {
      String trimmed = each.trim();
      if (!trimmed.isBlank()) {
        names.add(trimmed);
      }
    }
    return List.copyOf(names);
  }

  private static Instant sinceOf(String since) {
    if (since == null || since.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(since.trim()).truncatedTo(ChronoUnit.MICROS);
    } catch (DateTimeParseException notAnInstant) {
      throw new BadRequestException("since must be an ISO-8601 instant");
    }
  }

  /**
   * The payload search, lower-cased and wrapped in wildcards.
   *
   * <p>It is a <b>substring of the opaque payload string</b> and it is named {@code q} because that
   * is what it is. This server never parses a payload — the idempotent publish compares it byte for
   * byte, so parsing it here would be the first place that stopped being true — and there is no one
   * key to search anyway: a build names its repository under {@code repoId} and a release names it
   * under {@code repository}. Searching the whole string finds both, over-matches slightly, and says
   * so, which is the honest shape of the question a person is asking.
   *
   * <p>{@code %} and {@code _} in the caller's text are escaped, so "substring" means substring even
   * when the substring is {@code 100%}.
   */
  private static String searchOf(String q) {
    if (q == null || q.isBlank()) {
      return null;
    }
    return "%" + escapeForLike(q.trim()) + "%";
  }

  /**
   * Repeatable {@code key=value} filters, each turned into a {@code lower(payload) like …} pattern
   * matching the exact fragment {@code "key":"value"} a canonically-published payload holds for a
   * string-valued key — one filter narrowed to one field, the same scan {@link #searchOf} already
   * pays for the whole payload.
   *
   * <p><b>The closing quote is part of the literal</b>, deliberately: without it {@code
   * attr=packageType=dae} would match a value of {@code daemon}, because the pattern would be {@code
   * ?q=} wearing a key name rather than an exact match.
   *
   * <p>Blank entries are dropped — the rule {@code ?parentId=} already follows — and any entry with
   * no {@code =} at all, or an empty key, is a 400 naming the parameter: a filter that cannot say
   * which key it means is not a looser question, it is a malformed one.
   */
  private static List<String> attrFiltersOf(List<String> attr) {
    if (attr == null || attr.isEmpty()) {
      return List.of();
    }
    List<String> filters = new ArrayList<>();
    for (String each : attr) {
      if (each == null || each.isBlank()) {
        continue;
      }
      int separator = each.indexOf('=');
      String key = separator < 0 ? "" : each.substring(0, separator).trim();
      if (separator < 0 || key.isEmpty()) {
        throw new BadRequestException("attr must be key=value: " + each);
      }
      String value = each.substring(separator + 1).trim();
      filters.add("%\"" + escapeForLike(key) + "\":\"" + escapeForLike(value) + "\"%");
    }
    if (filters.size() > MAX_ATTR_FILTERS) {
      throw new BadRequestException("attr accepts at most " + MAX_ATTR_FILTERS + " filters");
    }
    return List.copyOf(filters);
  }

  /**
   * Lower-cased, with {@code %}/{@code _}/{@code !} escaped so a caller's literal character is never
   * read as a LIKE wildcard — shared by {@link #searchOf} and {@link #attrFiltersOf}, which differ
   * only in how they wrap the result.
   */
  private static String escapeForLike(String raw) {
    return raw.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_");
  }

  private static int limitOf(String limit) {
    if (limit == null || limit.isBlank()) {
      return DEFAULT_LIMIT;
    }
    int size;
    try {
      size = Integer.parseInt(limit.trim());
    } catch (NumberFormatException notANumber) {
      throw new BadRequestException("limit must be a whole number");
    }
    if (size < 1) {
      throw new BadRequestException("limit must be at least 1");
    }
    return Math.min(size, MAX_LIMIT);
  }
}
