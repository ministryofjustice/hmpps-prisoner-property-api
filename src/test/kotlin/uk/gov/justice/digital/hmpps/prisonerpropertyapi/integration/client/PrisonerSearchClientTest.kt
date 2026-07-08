package uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.client

import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
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

  @Test
  fun `getPrisoners looks up in bulk requesting only the fields the list needs`() {
    prisonerSearch.stubFindByNumbers("A1234BC" to "MDI", "A1111AA" to "LEI")

    val prisoners = prisonerSearchClient.getPrisoners(listOf("A1234BC", "A1111AA"))

    assertThat(prisoners.keys).containsExactlyInAnyOrder("A1234BC", "A1111AA")
    assertThat(prisoners["A1234BC"]?.prisonId).isEqualTo("MDI")
    prisonerSearch.verify(
      postRequestedFor(urlPathEqualTo("/prisoner-search/prisoner-numbers"))
        .withQueryParam("responseFields", equalTo("prisonerNumber,firstName,lastName,prisonId,lastMovementTypeCode")),
    )
  }

  @Test
  fun `getPrisoners chunks a large batch into multiple requests`() {
    prisonerSearch.stubFindByNumbers("A1234BC" to "MDI")

    prisonerSearchClient.getPrisoners((1..1001).map { "A%04dBC".format(it) })

    prisonerSearch.verify(2, postRequestedFor(urlPathEqualTo("/prisoner-search/prisoner-numbers")))
  }
}
