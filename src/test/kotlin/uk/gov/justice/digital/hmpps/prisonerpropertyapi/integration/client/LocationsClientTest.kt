package uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.client

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
}
