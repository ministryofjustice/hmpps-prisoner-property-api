package uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain

/**
 * How a search term staff type is matched.
 *
 * Partial and case-insensitive: someone reading a seal off a box, or half-remembering a storage location, can
 * type the part they have and still find the record. `*` (any run of characters) and `?` (a single character)
 * are honoured so a term can be anchored where that matters - the same vocabulary the box-location picker has
 * always used, so one search box does not behave two ways.
 *
 * Two forms of the same rule: [toWildcardRegex] for matching in memory, [toLikePattern] for matching in SQL.
 * They are kept together because they have to agree.
 */
object SearchTerm {

  /** The escape character for [toLikePattern], to be passed to `like(..., LIKE_ESCAPE)`. */
  const val LIKE_ESCAPE = '\\'

  /**
   * The term as a case-insensitive regex, escaping regex metacharacters but honouring the `*` and `?`
   * wildcards. Match with `containsMatchIn`, so a term with no wildcards matches anywhere in the value.
   */
  fun toWildcardRegex(term: String): Regex {
    val pattern = buildString {
      term.forEach { ch ->
        when (ch) {
          '*' -> append(".*")
          '?' -> append('.')
          else -> append(Regex.escape(ch.toString()))
        }
      }
    }
    return Regex(pattern, RegexOption.IGNORE_CASE)
  }

  /**
   * The term as a SQL LIKE pattern, surrounded by `%` so it matches anywhere in the value. The LIKE
   * metacharacters `%` and `_` are escaped so a term containing them matches them literally; the wildcards
   * staff can use, `*` and `?`, become their LIKE equivalents.
   *
   * Case is not handled here - compare against a lowered column with a lowered term.
   */
  fun toLikePattern(term: String): String {
    val pattern = buildString {
      term.forEach { ch ->
        when (ch) {
          '*' -> append('%')
          '?' -> append('_')
          '%', '_', LIKE_ESCAPE -> append(LIKE_ESCAPE).append(ch)
          else -> append(ch)
        }
      }
    }
    return "%$pattern%"
  }
}
