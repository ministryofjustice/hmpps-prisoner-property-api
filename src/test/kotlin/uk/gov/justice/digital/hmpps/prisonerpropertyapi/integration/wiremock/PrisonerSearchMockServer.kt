package uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.wiremock

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext

class PrisonerSearchApiExtension :
  BeforeAllCallback,
  AfterAllCallback,
  BeforeEachCallback {
  companion object {
    @JvmField
    val prisonerSearch = PrisonerSearchMockServer()
  }

  override fun beforeAll(context: ExtensionContext) {
    prisonerSearch.start()
  }

  override fun beforeEach(context: ExtensionContext) {
    prisonerSearch.resetAll()
  }

  override fun afterAll(context: ExtensionContext) {
    prisonerSearch.stop()
  }
}

class PrisonerSearchMockServer : WireMockServer(WIREMOCK_PORT) {
  companion object {
    private const val WIREMOCK_PORT = 8091
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

  fun stubGetPrisoner(
    prisonerNumber: String,
    prisonId: String = "MDI",
    lastMovementTypeCode: String = "ADM",
    confirmedReleaseDate: String? = null,
    conditionalReleaseDate: String? = null,
  ) {
    stubFor(
      get(urlPathEqualTo("/prisoner/$prisonerNumber")).willReturn(
        aResponse()
          .withHeader("Content-Type", "application/json")
          .withStatus(200)
          .withBody(prisonerJson(prisonerNumber, prisonId, lastMovementTypeCode, confirmedReleaseDate, conditionalReleaseDate)),
      ),
    )
  }

  /** Stub the bulk number lookup, returning one prisoner per supplied (prisonerNumber to prisonId) pair. */
  fun stubFindByNumbers(vararg prisoners: Pair<String, String>) = stubFindByNumbersWithMovement(
    *prisoners.map { (number, prison) -> Triple(number, prison, "ADM") }.toTypedArray(),
  )

  /**
   * Stub the bulk number lookup for a single prisoner carrying a confirmed release date - the field the
   * establishment list and summary use to surface stored property as due for return before release.
   */
  fun stubFindByNumbersWithReleaseDate(
    prisonerNumber: String,
    prisonId: String,
    confirmedReleaseDate: String,
    lastMovementTypeCode: String = "ADM",
  ) {
    stubFor(
      post(urlPathEqualTo("/prisoner-search/prisoner-numbers")).willReturn(
        aResponse()
          .withHeader("Content-Type", "application/json")
          .withStatus(200)
          .withBody("[${prisonerJson(prisonerNumber, prisonId, lastMovementTypeCode, confirmedReleaseDate)}]"),
      ),
    )
  }

  /**
   * Stub the bulk number lookup failing. The client degrades a failed chunk to no prisoners rather than an
   * error, so this is how "prisoner-search unavailable" is exercised: statuses and counts then fall back to
   * what each container itself records.
   */
  fun stubFindByNumbersFails() {
    stubFor(
      post(urlPathEqualTo("/prisoner-search/prisoner-numbers")).willReturn(
        aResponse()
          .withHeader("Content-Type", "application/json")
          .withStatus(500)
          .withBody("""{"status":500,"userMessage":"prisoner-search is unavailable"}"""),
      ),
    )
  }

  /** As [stubFindByNumbers] but each (prisonerNumber, prisonId, lastMovementTypeCode) triple controls the movement type. */
  fun stubFindByNumbersWithMovement(vararg prisoners: Triple<String, String, String>) {
    val body = prisoners.joinToString(prefix = "[", postfix = "]") { (number, prison, movement) ->
      prisonerJson(number, prison, movement)
    }
    stubFor(
      post(urlPathEqualTo("/prisoner-search/prisoner-numbers")).willReturn(
        aResponse()
          .withHeader("Content-Type", "application/json")
          .withStatus(200)
          .withBody(body),
      ),
    )
  }

  /**
   * Stub the prison roll - everyone currently at [prisonId]. The real endpoint returns a Spring page and only
   * the requested responseFields are populated, so the stub carries the paging envelope with prisoner numbers
   * alone. [totalAtPrison] defaults to the number supplied; pass a larger value to simulate a roll truncated
   * by the page size.
   */
  fun stubFindByPrison(prisonId: String, vararg prisonerNumbers: String, totalAtPrison: Int = prisonerNumbers.size) {
    val content = prisonerNumbers.joinToString(",") { """{"prisonerNumber":"$it"}""" }
    stubFor(
      get(urlPathEqualTo("/prisoner-search/prison/$prisonId")).willReturn(
        aResponse()
          .withHeader("Content-Type", "application/json")
          .withStatus(200)
          .withBody(
            """
            {
              "content": [$content],
              "totalElements": $totalAtPrison,
              "totalPages": 1,
              "number": 0,
              "size": 5000,
              "numberOfElements": ${prisonerNumbers.size},
              "first": true,
              "last": true,
              "empty": ${prisonerNumbers.isEmpty()}
            }
            """.trimIndent(),
          ),
      ),
    )
  }

  /**
   * Stub the prison roll failing. The client returns no roll rather than an error, so the establishment list
   * falls back to the transfer destinations recorded on the containers themselves.
   */
  fun stubFindByPrisonFails(prisonId: String) {
    stubFor(
      get(urlPathEqualTo("/prisoner-search/prison/$prisonId")).willReturn(
        aResponse()
          .withHeader("Content-Type", "application/json")
          .withStatus(500)
          .withBody("""{"status":500,"userMessage":"prisoner-search is unavailable"}"""),
      ),
    )
  }

  private fun prisonerJson(
    prisonerNumber: String,
    prisonId: String,
    lastMovementTypeCode: String,
    confirmedReleaseDate: String? = null,
    conditionalReleaseDate: String? = null,
  ): String {
    val releaseDates = listOfNotNull(
      confirmedReleaseDate?.let { """"confirmedReleaseDate": "$it"""" },
      conditionalReleaseDate?.let { """"conditionalReleaseDate": "$it"""" },
    ).joinToString("") { ",\n      $it" }
    return """
    {
      "prisonerNumber": "$prisonerNumber",
      "firstName": "JOHN",
      "lastName": "SMITH",
      "prisonId": "$prisonId",
      "prisonName": "Moorland (HMP & YOI)",
      "cellLocation": "1-1-001",
      "lastMovementTypeCode": "$lastMovementTypeCode"$releaseDates
    }
    """.trimIndent()
  }

  fun stubGetPrisonerNotFound(prisonerNumber: String) {
    stubFor(
      get(urlPathEqualTo("/prisoner/$prisonerNumber")).willReturn(
        aResponse()
          .withHeader("Content-Type", "application/json")
          .withStatus(404)
          .withBody("""{"status":404,"userMessage":"$prisonerNumber not found"}"""),
      ),
    )
  }
}
