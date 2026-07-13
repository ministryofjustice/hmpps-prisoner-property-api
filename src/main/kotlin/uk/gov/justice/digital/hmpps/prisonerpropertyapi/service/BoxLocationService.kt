package uk.gov.justice.digital.hmpps.prisonerpropertyapi.service

import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.client.LocationsClient
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainerRepository
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.BoxLocationDto
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.BoxLocationSort

@Service
class BoxLocationService(
  private val repository: PropertyContainerRepository,
  private val locationsClient: LocationsClient,
) {

  /**
   * A page of the property storage locations in a prison that still have room, each annotated with its
   * capacity and how many containers it currently holds, so a user can pick somewhere to store property.
   * A location can hold property if it has a PROPERTY usage (any location type, not only BOX); locations
   * with no remaining space (containers held >= capacity) are excluded, since you cannot store there.
   *
   * Optionally filtered by [query] - a case-insensitive match against the location code, local name and path
   * hierarchy. The query supports simple wildcards: `*` matches any run of characters and `?` matches a
   * single character; any other regex metacharacters are treated literally. A query with no wildcards is a
   * substring (contains) match.
   *
   * Sorted alphabetically by name, or - with [BoxLocationSort.MOST_AVAILABLE] - most spaces first. The
   * location set per prison is small and cached, so filtering, sorting and paging are done in memory.
   */
  @Transactional(readOnly = true)
  fun getBoxLocations(
    prisonId: String,
    sort: BoxLocationSort = BoxLocationSort.NAME,
    query: String? = null,
    pageable: Pageable = Pageable.unpaged(),
  ): Page<BoxLocationDto> {
    val locations = locationsClient.getPropertyLocations(prisonId)
    // The denormalised current_internal_location_id is the location id while a container is physically
    // present, and null once removed or held offsite - so this counts the containers actually in each
    // location without loading any events.
    val countsByLocation = repository.countContainersByLocation(prisonId)
      .associate { it.locationId to it.count.toInt() }

    val matcher = query?.trim()?.takeIf { it.isNotEmpty() }?.let { toWildcardRegex(it) }
    val rows = locations.asSequence()
      .map { BoxLocationDto.from(it, countsByLocation[it.id] ?: 0) }
      .filter { it.availableSpaces > 0 }
      .filter { matcher == null || it.matches(matcher) }
      .toList()

    val sorted = when (sort) {
      BoxLocationSort.NAME -> rows.sortedBy { it.name.lowercase() }
      BoxLocationSort.MOST_AVAILABLE -> rows.sortedWith(compareByDescending<BoxLocationDto> { it.availableSpaces }.thenBy { it.name.lowercase() })
    }

    if (pageable.isUnpaged) return PageImpl(sorted)
    val from = pageable.offset.toInt().coerceAtMost(sorted.size)
    val to = (from + pageable.pageSize).coerceAtMost(sorted.size)
    return PageImpl(sorted.subList(from, to), pageable, sorted.size.toLong())
  }

  private fun BoxLocationDto.matches(matcher: Regex) = matcher.containsMatchIn(code) || matcher.containsMatchIn(pathHierarchy) || (localName?.let { matcher.containsMatchIn(it) } ?: false)

  /**
   * Turn a user search term into a case-insensitive regex, escaping regex metacharacters but honouring
   * `*` (any run of characters) and `?` (a single character) as wildcards.
   */
  private fun toWildcardRegex(query: String): Regex {
    val pattern = buildString {
      query.forEach { ch ->
        when (ch) {
          '*' -> append(".*")
          '?' -> append('.')
          else -> append(Regex.escape(ch.toString()))
        }
      }
    }
    return Regex(pattern, RegexOption.IGNORE_CASE)
  }
}
