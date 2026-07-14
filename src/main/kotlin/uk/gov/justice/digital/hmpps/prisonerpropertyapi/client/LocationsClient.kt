package uk.gov.justice.digital.hmpps.prisonerpropertyapi.client

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.bodyToMono
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.CreatePropertyLocationRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.UpdatePropertyLocationRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.DuplicatePropertyLocationNameException
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.PropertyLocationNotFoundException
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
   * Look up a single property storage location by its id, with its capacity. Returns null if the id is
   * unknown or the location cannot store property (locations-inside-prison returns 404 in both cases).
   * Used to validate that a container's internal location can actually hold property.
   */
  fun getPropertyLocation(id: UUID): PropertyLocation? {
    log.debug("Looking up property location {}", id)
    try {
      return locationsWebClient
        .get()
        .uri("/locations/property/{id}", id)
        .retrieve()
        .bodyToMono<PropertyLocation>()
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
   * storage-location term (code, local name or path hierarchy) to its location id(s). Returns an empty list
   * if the prison has none or is unknown.
   *
   * Read live on every call (not cached). The service runs multiple pods, so a per-pod cache made an admin's
   * add/rename/re-capacity/remove appear only on the pod that made it (and the write-time capacity guard,
   * which already reads live, could then disagree with the cached picker) until a scheduled evict caught up.
   * locations-inside-prison is a cheap internal call, so we read it live and stay consistent across pods.
   */
  fun getPropertyLocations(prisonId: String): List<PropertyLocation> = fetchPropertyLocations(prisonId)

  private fun fetchPropertyLocations(prisonId: String): List<PropertyLocation> {
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

  /**
   * Create a new property storage location in a prison (a top-level BOX location with a generated code and
   * a PROPERTY usage carrying the capacity). Translates a downstream conflict into a domain
   * [DuplicatePropertyLocationNameException] so the name clash surfaces cleanly to the caller.
   */
  fun createPropertyLocation(prisonId: String, request: CreatePropertyLocationRequest): PropertyLocation {
    try {
      return locationsWebClient
        .post()
        .uri("/locations/prison/{prisonId}/property", prisonId)
        .bodyValue(request)
        .retrieve()
        .bodyToMono<PropertyLocation>()
        .block()!!
    } catch (ex: WebClientResponseException) {
      if (ex.statusCode == HttpStatus.CONFLICT) throw DuplicatePropertyLocationNameException(request.localName)
      throw ex
    }
  }

  /**
   * Update a property storage location's name and/or capacity. Translates a downstream 404 into
   * [PropertyLocationNotFoundException] and a name clash (409) into [DuplicatePropertyLocationNameException].
   */
  fun updatePropertyLocation(id: UUID, request: UpdatePropertyLocationRequest): PropertyLocation {
    try {
      return locationsWebClient
        .put()
        .uri("/locations/property/{id}", id)
        .bodyValue(request)
        .retrieve()
        .bodyToMono<PropertyLocation>()
        .block()!!
    } catch (ex: WebClientResponseException) {
      when (ex.statusCode) {
        HttpStatus.NOT_FOUND -> throw PropertyLocationNotFoundException(id)
        HttpStatus.CONFLICT -> throw DuplicatePropertyLocationNameException(request.localName ?: "")
        else -> throw ex
      }
    }
  }

  /**
   * Remove the property designation from a location (drops its PROPERTY usage). Translates a downstream 404
   * into [PropertyLocationNotFoundException].
   */
  fun removePropertyLocation(id: UUID): PropertyLocation {
    try {
      return locationsWebClient
        .delete()
        .uri("/locations/property/{id}", id)
        .retrieve()
        .bodyToMono<PropertyLocation>()
        .block()!!
    } catch (ex: WebClientResponseException) {
      if (ex.statusCode == HttpStatus.NOT_FOUND) throw PropertyLocationNotFoundException(id)
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
 * A single non-residential location from the locations-inside-prison batch lookup, used to resolve a
 * location id to a human-friendly name.
 */
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
}
