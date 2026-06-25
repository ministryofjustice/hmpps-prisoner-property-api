package uk.gov.justice.digital.hmpps.prisonerpropertyapi.client

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.bodyToMono
import java.util.UUID

/**
 * Calls locations-inside-prison-api to resolve a property's internal location id - used to validate
 * that a location UUID exists and to return its name rather than the raw UUID.
 */
@Component
class LocationsClient(
  @param:Qualifier("locationsWebClient") private val locationsWebClient: WebClient,
) {

  companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
  }

  /**
   * Look up a location by its id. Returns null if not found.
   */
  fun getLocation(id: UUID): LocationDetail? {
    log.debug("Looking up location {}", id)
    try {
      return locationsWebClient
        .get()
        .uri("/locations/{id}", id)
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
}
