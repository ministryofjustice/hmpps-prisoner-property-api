package uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.client

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.cache.CacheManager
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.client.LocationsClient
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.wiremock.HmppsAuthApiExtension.Companion.hmppsAuth
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.wiremock.LocationsApiExtension.Companion.locations
import java.util.UUID

class LocationsClientTest : IntegrationTestBase() {

  @Autowired
  private lateinit var locationsClient: LocationsClient

  @Autowired
  private lateinit var cacheManager: CacheManager

  @BeforeEach
  fun stubToken() {
    hmppsAuth.stubGrantToken()
    // getLocationsByType is @Cacheable - clear so each case resolves against its own stub, not a prior one's.
    cacheManager.cacheNames.forEach { cacheManager.getCache(it)?.clear() }
  }

  @Test
  fun `returns location detail with a display name`() {
    val id = UUID.fromString("11111111-1111-1111-1111-111111111111")
    locations.stubGetLocation(id.toString())

    val location = locationsClient.getLocation(id)

    assertThat(location).isNotNull
    assertThat(location!!.id).isEqualTo(id)
    assertThat(location.prisonId).isEqualTo("MDI")
    assertThat(location.displayName()).isEqualTo("Reception Property Store")
  }

  @Test
  fun `returns null when the location is not found`() {
    val id = UUID.fromString("22222222-2222-2222-2222-222222222222")
    locations.stubGetLocationNotFound(id.toString())

    assertThat(locationsClient.getLocation(id)).isNull()
  }

  @Test
  fun `getLocations resolves several ids in one batch call, keyed by id`() {
    val id1 = UUID.fromString("11111111-1111-1111-1111-111111111111")
    val id2 = UUID.fromString("22222222-2222-2222-2222-222222222222")
    locations.stubPostLocationsBatch(id1.toString(), id2.toString())

    val result = locationsClient.getLocations(listOf(id1, id2, id1))

    assertThat(result.keys).containsExactlyInAnyOrder(id1, id2)
    assertThat(result[id1]!!.displayName()).isEqualTo("Reception Property Store")
  }

  @Test
  fun `getLocations returns an empty map without calling the api when there are no ids`() {
    assertThat(locationsClient.getLocations(emptyList())).isEmpty()
  }

  @Test
  fun `getLocations degrades gracefully to an empty map when the batch call fails`() {
    locations.stubPostLocationsBatchError(500)

    assertThat(locationsClient.getLocations(listOf(UUID.randomUUID()))).isEmpty()
  }

  @Test
  fun `getLocationsByType returns the boxes for a prison`() {
    val box1 = "11111111-1111-1111-1111-111111111111"
    val box2 = "22222222-2222-2222-2222-222222222222"
    locations.stubGetBoxLocations(
      "LEI",
      listOf(Triple(box1, "PROP1", "Property Box 1"), Triple(box2, "PROP2", "Property Box 2")),
    )

    val result = locationsClient.getLocationsByType("LEI", "BOX")

    assertThat(result.map { it.id.toString() }).containsExactly(box1, box2)
    assertThat(result[0].displayName()).isEqualTo("Property Box 1")
  }

  @Test
  fun `getLocationsByType returns an empty list when the prison has no such locations`() {
    assertThat(locationsClient.getLocationsByType("LEI", "BOX")).isEmpty()
  }
}
