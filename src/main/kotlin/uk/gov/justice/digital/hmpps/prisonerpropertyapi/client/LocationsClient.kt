package uk.gov.justice.digital.hmpps.prisonerpropertyapi.client

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.cache.annotation.Cacheable
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.bodyToMono
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.config.CacheConfiguration
import java.util.UUID

/**
 * Calls locations-inside-prison-api to resolve a property's internal location id - used to validate
 * that a location is a known non-residential location and to return its name rather than the raw UUID.
 */
@Component
class LocationsClient(
  @param:Qualifier("locationsWebClient") private val locationsWebClient: WebClient,
) {

  companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
  }

  /**
   * Look up a non-residential location by its id. Returns null if it is not a known non-residential
   * location (the endpoint returns 404 for unknown or residential ids).
   */
  fun getLocation(id: UUID): LocationDetail? {
    log.debug("Looking up non-residential location {}", id)
    try {
      return locationsWebClient
        .get()
        .uri("/locations/non-residential/{id}", id)
        .retrieve()
        .bodyToMono<LocationDetail>()
        .block()
    } catch (ex: WebClientResponseException) {
      if (ex.statusCode == HttpStatus.NOT_FOUND) {
        return null
      }
      throw ex
    }
  }

  /**
   * Resolve several non-residential locations at once, keyed by id, in a single call to
   * locations-inside-prison-api. Ids are de-duplicated; ids that are not non-residential locations (or
   * are unknown) are simply absent from the result. Degrades gracefully: if the batch call fails the
   * caller gets an empty map (and so null location names) rather than a failed read.
   */
  fun getLocations(ids: Collection<UUID>): Map<UUID, LocationDetail> {
    val distinctIds = ids.distinct()
    if (distinctIds.isEmpty()) return emptyMap()
    return try {
      locationsWebClient
        .post()
        .uri("/locations/non-residential/batch")
        .bodyValue(distinctIds)
        .retrieve()
        .bodyToMono<List<LocationDetail>>()
        .block()
        ?.associateBy { it.id }
        ?: emptyMap()
    } catch (ex: WebClientResponseException) {
      log.warn("Batch location lookup failed ({}), returning no location names", ex.statusCode)
      emptyMap()
    }
  }

  /**
   * The leaf locations in a prison that can hold property, each with the capacity of its PROPERTY usage.
   * A location can hold property iff it has a non-residential usage of type PROPERTY (any location type),
   * not only BOX-typed locations. Used both to list a prison's storage locations and to resolve a searched
   * storage-location term (code, local name or path hierarchy) to its location id(s). Cached per prison -
   * the set rarely changes. Returns an empty list if the prison has none or is unknown.
   */
  @Cacheable(CacheConfiguration.PROPERTY_LOCATIONS_CACHE_NAME)
  fun getPropertyLocations(prisonId: String): List<PropertyLocation> {
    log.debug("Looking up property locations for prison {}", prisonId)
    try {
      return locationsWebClient
        .get()
        .uri("/locations/prison/{prisonId}/property", prisonId)
        .retrieve()
        .bodyToMono<List<PropertyLocation>>()
        .block()
        ?: emptyList()
    } catch (ex: WebClientResponseException) {
      if (ex.statusCode == HttpStatus.NOT_FOUND) {
        return emptyList()
      }
      throw ex
    }
  }
}

/**
 * A location that can hold property, from the locations-inside-prison property endpoint, with the
 * capacity of its PROPERTY usage flattened out.
 */
data class PropertyLocation(
  val id: UUID,
  val prisonId: String,
  val code: String,
  val pathHierarchy: String,
  val localName: String? = null,
  val locationType: String? = null,
  val capacity: Int? = null,
) {
  /** A human-friendly location name, preferring the local name and falling back to the path hierarchy. */
  fun displayName(): String = localName ?: pathHierarchy
}

/**
 * A single non-residential location from the locations-inside-prison lookup. Carries its non-residential
 * usages so we can tell whether it can hold property (a PROPERTY usage) and, if so, its capacity.
 */
data class LocationDetail(
  val id: UUID,
  val prisonId: String,
  val code: String,
  val pathHierarchy: String,
  val localName: String? = null,
  val locationType: String? = null,
  val usage: List<NonResidentialUsage>? = null,
) {
  /** A human-friendly location name, preferring the local name and falling back to the path hierarchy. */
  fun displayName(): String = localName ?: pathHierarchy

  private fun propertyUsage(): NonResidentialUsage? = usage?.firstOrNull { it.usageType == PROPERTY_USAGE }

  /** Whether this location can hold property, i.e. it has a PROPERTY non-residential usage. */
  fun canStoreProperty(): Boolean = propertyUsage() != null

  /** The capacity of this location's PROPERTY usage (how many containers it can hold), or 0 if not set. */
  fun propertyCapacity(): Int = propertyUsage()?.capacity ?: 0

  private companion object {
    const val PROPERTY_USAGE = "PROPERTY"
  }
}

/** A non-residential usage of a location, as returned by the locations-inside-prison single lookup. */
data class NonResidentialUsage(
  val usageType: String,
  val capacity: Int? = null,
)
