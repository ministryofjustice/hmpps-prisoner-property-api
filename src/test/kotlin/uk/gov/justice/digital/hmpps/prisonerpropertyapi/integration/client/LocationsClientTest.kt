package uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.client

import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.client.LocationsClient
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.wiremock.HmppsAuthApiExtension.Companion.hmppsAuth
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.wiremock.LocationsApiExtension.Companion.locations
import java.util.UUID

class LocationsClientTest : IntegrationTestBase() {

  @Autowired
  private lateinit var locationsClient: LocationsClient

  @BeforeEach
  fun stubToken() {
    hmppsAuth.stubGrantToken()
  }

  @Test
  fun `returns the property location with its capacity`() {
    val id = UUID.fromString("11111111-1111-1111-1111-111111111111")
    locations.stubGetPropertyLocation(id.toString(), propertyCapacity = 40)

    val location = locationsClient.getPropertyLocation(id)

    assertThat(location).isNotNull
    assertThat(location!!.id).isEqualTo(id)
    assertThat(location.prisonId).isEqualTo("MDI")
    assertThat(location.capacity).isEqualTo(40)
    assertThat(location.displayName()).isEqualTo("Reception Property Store")
  }

  @Test
  fun `returns null when the location is not found or cannot store property`() {
    val id = UUID.fromString("22222222-2222-2222-2222-222222222222")
    locations.stubGetPropertyLocationNotFound(id.toString())

    assertThat(locationsClient.getPropertyLocation(id)).isNull()
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
  fun `getPropertyLocations returns the property locations for a prison with their capacity`() {
    val box1 = "11111111-1111-1111-1111-111111111111"
    val box2 = "22222222-2222-2222-2222-222222222222"
    locations.stubGetBoxLocations(
      "LEI",
      listOf(Triple(box1, "PROP1", "Property Box 1"), Triple(box2, "PROP2", "Property Box 2")),
      capacity = 25,
    )

    val result = locationsClient.getPropertyLocations("LEI")

    assertThat(result.map { it.id.toString() }).containsExactly(box1, box2)
    assertThat(result[0].displayName()).isEqualTo("Property Box 1")
    assertThat(result[0].capacity).isEqualTo(25)
  }

  @Test
  fun `getPropertyLocations returns an empty list when the prison has no such locations`() {
    assertThat(locationsClient.getPropertyLocations("LEI")).isEmpty()
  }

  @Test
  fun `getPropertyLocations is not cached - it reads live on every call`() {
    locations.stubGetBoxLocations("LEI", listOf(Triple(BOX1, "PROP1", "Property Box 1")))

    // Two reads hit the api twice (no per-pod cache) - this is what keeps capacity/validation consistent
    // across pods and lets an admin see their own add/rename/re-capacity/remove on the very next read.
    val first = locationsClient.getPropertyLocations("LEI")
    val second = locationsClient.getPropertyLocations("LEI")

    assertThat(first.map { it.id.toString() }).containsExactly(BOX1)
    assertThat(second.map { it.id.toString() }).containsExactly(BOX1)
    locations.verify(2, getRequestedFor(urlPathEqualTo("/locations/prison/LEI/property")))
  }

  private companion object {
    const val BOX1 = "11111111-1111-1111-1111-111111111111"
  }
}
