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
   * A page of the BOX locations available in a prison, each annotated with how many containers are currently
   * held there, so a user can pick a suitable place to store property. Empty boxes are included (count 0).
   *
   * Optionally filtered by [query] - a case-insensitive match against the box code, local name and path
   * hierarchy. The query supports simple wildcards: `*` matches any run of characters and `?` matches a
   * single character; any other regex metacharacters are treated literally. A query with no wildcards is a
   * substring (contains) match.
   *
   * Sorted alphabetically by name, or - with [BoxLocationSort.FEWEST_CONTAINERS] - emptiest first. The box
   * set per prison is small and cached, so filtering, sorting and paging are done in memory.
   */
  @Transactional(readOnly = true)
  fun getBoxLocations(
    prisonId: String,
    sort: BoxLocationSort = BoxLocationSort.NAME,
    query: String? = null,
    pageable: Pageable = Pageable.unpaged(),
  ): Page<BoxLocationDto> {
    val boxes = locationsClient.getLocationsByType(prisonId, BOX)
    // The denormalised current_internal_location_id is the box id while a container is physically present,
    // and null once removed or held offsite - so this counts the containers actually in each box without
    // loading any events.
    val countsByLocation = repository.countContainersByLocation(prisonId)
      .associate { it.locationId to it.count.toInt() }

    val matcher = query?.trim()?.takeIf { it.isNotEmpty() }?.let { toWildcardRegex(it) }
    val rows = boxes.asSequence()
      .map { BoxLocationDto.from(it, countsByLocation[it.id] ?: 0) }
      .filter { matcher == null || it.matches(matcher) }
      .toList()

    val sorted = when (sort) {
      BoxLocationSort.NAME -> rows.sortedBy { it.name.lowercase() }
      BoxLocationSort.FEWEST_CONTAINERS -> rows.sortedWith(compareBy({ it.containerCount }, { it.name.lowercase() }))
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

  private companion object {
    private const val BOX = "BOX"
  }
}
