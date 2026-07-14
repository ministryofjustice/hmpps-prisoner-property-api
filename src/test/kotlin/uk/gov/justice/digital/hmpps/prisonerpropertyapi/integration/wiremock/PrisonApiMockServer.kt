package uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.wiremock

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext

class PrisonApiExtension :
  BeforeAllCallback,
  AfterAllCallback,
  BeforeEachCallback {
  companion object {
    @JvmField
    val prisonApi = PrisonApiMockServer()
  }

  override fun beforeAll(context: ExtensionContext) {
    prisonApi.start()
  }

  override fun beforeEach(context: ExtensionContext) {
    prisonApi.resetAll()
  }

  override fun afterAll(context: ExtensionContext) {
    prisonApi.stop()
  }
}

class PrisonApiMockServer : WireMockServer(WIREMOCK_PORT) {
  companion object {
    private const val WIREMOCK_PORT = 8094
  }

  fun stubHealthPing(status: Int) {
    stubFor(
      get("/health/ping").willReturn(
        aResponse()
          .withHeader("Content-Type", "application/json")
          .withBody(if (status == 200) """{"status":"UP"}""" else """{"status":"DOWN"}""")
          .withStatus(status),
      ),
    )
  }

  /**
   * Stub the prison-timeline as a single booking. [admissions] and [transfers] are (prisonId to
   * dateInToPrison ISO date-time) pairs, becoming ADM movements and transfers respectively.
   */
  fun stubGetPrisonTimeline(
    prisonerNumber: String,
    admissions: List<Pair<String, String>> = emptyList(),
    transfers: List<Pair<String, String>> = emptyList(),
  ) {
    val movementDates = admissions.joinToString(",") { (prisonId, date) ->
      """{ "inwardType": "ADM", "dateInToPrison": "$date", "admittedIntoPrisonId": "$prisonId" }"""
    }
    val transferJson = transfers.joinToString(",") { (prisonId, date) ->
      """{ "dateInToPrison": "$date", "toPrisonId": "$prisonId" }"""
    }
    val body = """
      {
        "prisonerNumber": "$prisonerNumber",
        "prisonPeriod": [
          { "movementDates": [$movementDates], "transfers": [$transferJson] }
        ]
      }
    """.trimIndent()
    stubTimelineResponse(prisonerNumber, 200, body)
  }

  /** Stub an empty prison-timeline (prisoner known but no periods). */
  fun stubGetPrisonTimelineEmpty(prisonerNumber: String) = stubTimelineResponse(
    prisonerNumber,
    200,
    """{ "prisonerNumber": "$prisonerNumber", "prisonPeriod": [] }""",
  )

  /** Stub an error status (e.g. 404 not found, or 403 before the VIEW_PRISONER_DATA role is granted). */
  fun stubGetPrisonTimelineError(prisonerNumber: String, status: Int) = stubTimelineResponse(
    prisonerNumber,
    status,
    """{ "status": $status, "userMessage": "$prisonerNumber" }""",
  )

  private fun stubTimelineResponse(prisonerNumber: String, status: Int, body: String) {
    stubFor(
      get(urlPathEqualTo("/api/offenders/$prisonerNumber/prison-timeline")).willReturn(
        aResponse()
          .withHeader("Content-Type", "application/json")
          .withStatus(status)
          .withBody(body),
      ),
    )
  }
}
