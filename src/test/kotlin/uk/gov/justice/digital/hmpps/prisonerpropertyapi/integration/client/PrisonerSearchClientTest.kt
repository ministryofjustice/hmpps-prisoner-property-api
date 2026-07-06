package uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.client

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.client.PrisonerSearchClient
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.wiremock.HmppsAuthApiExtension.Companion.hmppsAuth
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.wiremock.PrisonerSearchApiExtension.Companion.prisonerSearch

class PrisonerSearchClientTest : IntegrationTestBase() {

  @Autowired
  private lateinit var prisonerSearchClient: PrisonerSearchClient

  @BeforeEach
  fun stubToken() {
    hmppsAuth.stubGrantToken()
  }

  @Test
  fun `returns the prisoner name and current location`() {
    prisonerSearch.stubGetPrisoner("A1234BC")

    val prisoner = prisonerSearchClient.getPrisoner("A1234BC")

    assertThat(prisoner).isNotNull
    assertThat(prisoner!!.prisonerNumber).isEqualTo("A1234BC")
    assertThat(prisoner.firstName).isEqualTo("JOHN")
    assertThat(prisoner.lastName).isEqualTo("SMITH")
    assertThat(prisoner.prisonId).isEqualTo("MDI")
    assertThat(prisoner.cellLocation).isEqualTo("1-1-001")
    assertThat(prisoner.lastMovementTypeCode).isEqualTo("ADM")
  }

  @Test
  fun `returns null when the prisoner is not found`() {
    prisonerSearch.stubGetPrisonerNotFound("A0000AA")

    assertThat(prisonerSearchClient.getPrisoner("A0000AA")).isNull()
  }
}
