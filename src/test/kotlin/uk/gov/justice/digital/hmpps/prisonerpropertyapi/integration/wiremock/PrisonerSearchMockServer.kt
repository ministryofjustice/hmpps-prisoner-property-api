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

  fun stubGetPrisoner(prisonerNumber: String) {
    stubFor(
      get(urlPathEqualTo("/prisoner/$prisonerNumber")).willReturn(
        aResponse()
          .withHeader("Content-Type", "application/json")
          .withStatus(200)
          .withBody(
            """
              {
                "prisonerNumber": "$prisonerNumber",
                "firstName": "JOHN",
                "lastName": "SMITH",
                "prisonId": "MDI",
                "prisonName": "Moorland (HMP & YOI)",
                "cellLocation": "1-1-001"
              }
            """.trimIndent(),
          ),
      ),
    )
  }

  /** Stub the bulk number lookup, returning one prisoner per supplied (prisonerNumber to prisonId) pair. */
  fun stubFindByNumbers(vararg prisoners: Pair<String, String>) {
    val body = prisoners.joinToString(prefix = "[", postfix = "]") { (number, prison) ->
      """
        {
          "prisonerNumber": "$number",
          "firstName": "JOHN",
          "lastName": "SMITH",
          "prisonId": "$prison",
          "prisonName": "Moorland (HMP & YOI)",
          "cellLocation": "1-1-001"
        }
      """.trimIndent()
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
