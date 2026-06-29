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
   * All active non-residential locations for a prison (including property boxes), used to resolve a
   * searched storage-location code to its location id(s). Cached per prison - the set rarely changes.
   * Degrades gracefully to an empty list if the call fails. Uses the deprecated per-prison endpoint
   * because the newer summary endpoint excludes BOX locations, which is exactly where property is stored.
   */
  @Cacheable(CacheConfiguration.NON_RESIDENTIAL_LOCATIONS_CACHE_NAME)
  fun getNonResidentialLocations(prisonId: String): List<LocationDetail> {
    log.debug("Looking up non-residential locations for prison {}", prisonId)
    return try {
      locationsWebClient
        .get()
        .uri("/locations/prison/{prisonId}/non-residential", prisonId)
        .retrieve()
        .bodyToMono<List<LocationDetail>>()
        .block()
        ?: emptyList()
    } catch (ex: WebClientResponseException) {
      log.warn("Non-residential locations lookup for {} failed ({}), returning none", prisonId, ex.statusCode)
      emptyList()
    }
  }
}

data class LocationDetail(
  val id: UUID,
  val prisonId: String,
  val code: String,
  val pathHierarchy: String,
  val localName: String? = null,
  val locationType: String? = null,
) {
  /** A human-friendly location name, preferring the local name and falling back to the path hierarchy. */
  fun displayName(): String = localName ?: pathHierarchy

  /** Whether this location is a property box - the only location type property may be stored in. */
  fun isBox(): Boolean = locationType == "BOX"
}
