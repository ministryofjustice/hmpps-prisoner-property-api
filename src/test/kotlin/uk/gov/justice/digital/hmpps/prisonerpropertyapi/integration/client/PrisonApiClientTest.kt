package uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.client

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.client.PrisonApiClient
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.wiremock.HmppsAuthApiExtension.Companion.hmppsAuth
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.wiremock.PrisonApiExtension.Companion.prisonApi

class PrisonApiClientTest : IntegrationTestBase() {

  @Autowired
  private lateinit var prisonApiClient: PrisonApiClient

  @BeforeEach
  fun stubToken() {
    hmppsAuth.stubGrantToken()
  }

  @Test
  fun `returns the prison-timeline with admissions and transfers`() {
    prisonApi.stubGetPrisonTimeline(
      "A1234BC",
      admissions = listOf("LEI" to "2026-01-01T09:00:00"),
      transfers = listOf("MDI" to "2026-03-01T10:00:00"),
    )

    val summary = prisonApiClient.getPrisonTimeline("A1234BC")

    assertThat(summary).isNotNull
    val period = summary!!.prisonPeriod.single()
    assertThat(period.movementDates.single().inwardType).isEqualTo("ADM")
    assertThat(period.movementDates.single().admittedIntoPrisonId).isEqualTo("LEI")
    assertThat(period.transfers.single().toPrisonId).isEqualTo("MDI")
  }

  @Test
  fun `returns null when the prisoner is not found`() {
    prisonApi.stubGetPrisonTimelineError("A0000AA", 404)

    assertThat(prisonApiClient.getPrisonTimeline("A0000AA")).isNull()
  }

  @Test
  fun `degrades to null when access is forbidden (role not yet granted)`() {
    prisonApi.stubGetPrisonTimelineError("A1234BC", 403)

    assertThat(prisonApiClient.getPrisonTimeline("A1234BC")).isNull()
  }
}
