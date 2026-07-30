package uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.client

import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
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
    // The confirmed release date is requested too: the establishment list and summary need it to show
    // stored property as due for return ahead of release, matching the person view.
    prisonerSearch.verify(
      postRequestedFor(urlPathEqualTo("/prisoner-search/prisoner-numbers"))
        .withQueryParam(
          "responseFields",
          equalTo("prisonerNumber,firstName,lastName,prisonId,lastMovementTypeCode,confirmedReleaseDate"),
        ),
    )
  }

  @Test
  fun `getPrisoners chunks a large batch into multiple requests`() {
    prisonerSearch.stubFindByNumbers("A1234BC" to "MDI")

    prisonerSearchClient.getPrisoners((1..1001).map { "A%04dBC".format(it) })

    prisonerSearch.verify(2, postRequestedFor(urlPathEqualTo("/prisoner-search/prisoner-numbers")))
  }

  @Test
  fun `getPrisonRoll returns everyone at the prison, asking only for their numbers`() {
    prisonerSearch.stubFindByPrison("MDI", "A1234BC", "B5678CD")

    val roll = prisonerSearchClient.getPrisonRoll("MDI")

    assertThat(roll?.prisonerNumbers).containsExactlyInAnyOrder("A1234BC", "B5678CD")
    assertThat(roll?.totalAtPrison).isEqualTo(2)
    assertThat(roll?.isTruncated()).isFalse()

    prisonerSearch.verify(
      getRequestedFor(urlPathEqualTo("/prisoner-search/prison/MDI"))
        // Nothing else is needed, and the endpoint warns about response size.
        .withQueryParam("responseFields", equalTo("prisonerNumber"))
        // One large page: the endpoint sorts by relevance score, which makes paging unreliable.
        .withQueryParam("size", equalTo("5000"))
        .withQueryParam("page", equalTo("0"))
        // The endpoint declares consumes=application/json even for this GET, so without the header it 415s.
        .withHeader("Content-Type", equalTo("application/json"))
        // Restricted patients match on a supporting prison rather than being held here, so they must not be
        // in the roll - their property elsewhere is not this prison's to receive.
        .withoutQueryParam("include-restricted-patients"),
    )
  }

  @Test
  fun `getPrisonRoll reports a roll cut short by the page size`() {
    prisonerSearch.stubFindByPrison("MDI", "A1234BC", totalAtPrison = 6000)

    val roll = prisonerSearchClient.getPrisonRoll("MDI")

    assertThat(roll?.prisonerNumbers).containsExactly("A1234BC")
    assertThat(roll?.totalAtPrison).isEqualTo(6000)
    assertThat(roll?.isTruncated()).isTrue()
  }

  @Test
  fun `getPrisonRoll returns an empty roll for a prison holding nobody`() {
    prisonerSearch.stubFindByPrison("MDI")

    val roll = prisonerSearchClient.getPrisonRoll("MDI")

    assertThat(roll?.prisonerNumbers).isEmpty()
    assertThat(roll?.isTruncated()).isFalse()
  }

  @Test
  fun `getPrisonRoll returns no roll when the lookup fails, rather than an empty one`() {
    // The distinction matters: no roll means "fall back to what the containers record", an empty roll means
    // "nobody is here", and the two must not be confused.
    prisonerSearch.stubFindByPrisonFails("MDI")

    assertThat(prisonerSearchClient.getPrisonRoll("MDI")).isNull()
  }
}
