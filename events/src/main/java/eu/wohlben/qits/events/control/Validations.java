package eu.wohlben.qits.events.control;

import eu.wohlben.qits.events.error.BadRequestException;
import java.util.UUID;

/** Small shared control-layer guards for the events services. */
final class Validations {

  private Validations() {}

  /** Throws {@link BadRequestException} if {@code value} is null or blank. */
  static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new BadRequestException(field + " is required");
    }
  }

  /** Throws {@link BadRequestException} if {@code value} is null. */
  static void requirePresent(Object value, String field) {
    if (value == null) {
      throw new BadRequestException(field + " is required");
    }
  }

  /**
   * Throws {@link BadRequestException} unless {@code value} is a UUID in canonical form.
   *
   * <p>Only the publish path demands this — {@code GET}, {@code DELETE} and everything the manual
   * {@code POST} creates take any String id, because the id of a hand-recorded event is not the
   * publisher's business. On {@code PUT} the id <em>is</em> the idempotency key, and a caller that
   * cannot spell one has no retry-safe identity to offer.
   *
   * <p>The round-trip check is not redundant with {@link UUID#fromString}: that parser accepts
   * short groups ({@code 1-1-1-1-1}) and would silently store an id that is not the one the caller
   * would send back on a retry.
   */
  static void requireUuid(String value, String field) {
    requireText(value, field);
    boolean canonical;
    try {
      canonical = UUID.fromString(value).toString().equalsIgnoreCase(value);
    } catch (IllegalArgumentException notAUuid) {
      canonical = false;
    }
    if (!canonical) {
      throw new BadRequestException(field + " must be a UUID");
    }
  }

  /**
   * {@link #requireUuid}, but null is fine — the shape an <em>optional</em> id takes.
   *
   * <p>Null and blank are both "no value": a JSON field that is absent binds to null, and one sent
   * as {@code ""} is a client that meant to say nothing and said it clumsily. Neither is an error,
   * and neither is stored — see {@code EventService}, which normalises before it compares, so that
   * "no parent" is one value rather than two that fail to replay as each other.
   */
  static void requireUuidIfPresent(String value, String field) {
    if (value == null || value.isBlank()) {
      return;
    }
    requireUuid(value, field);
  }

  /**
   * Throws {@link BadRequestException} unless {@code value} is a dns-safe environment name — null
   * and blank are fine, the shape an <em>optional</em> field takes (see {@link
   * #requireUuidIfPresent} for why both read as "no value").
   *
   * <p>The rule mirrors what qits-deployments accepts as an environment name: lowercase letters and
   * digits, dashes inside but not at either end, at most 63 characters. {@code platform} — the
   * literal a publisher serving every tier stamps — passes like any other name. The guard is about
   * shape, not existence; whether such an environment exists is deliberately never checked.
   */
  static void requireEnvironmentIfPresent(String value, String field) {
    if (value == null || value.isBlank()) {
      return;
    }
    if (value.length() > 63 || !value.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")) {
      throw new BadRequestException(field + " must be a dns-safe environment name");
    }
  }
}
