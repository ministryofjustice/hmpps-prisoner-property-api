package uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.wiremock

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext

class LocationsApiExtension :
  BeforeAllCallback,
  AfterAllCallback,
  BeforeEachCallback {
  companion object {
    @JvmField
    val locations = LocationsMockServer()
  }

  override fun beforeAll(context: ExtensionContext) {
    locations.start()
  }

  override fun beforeEach(context: ExtensionContext) {
    locations.resetAll()
  }

  override fun afterAll(context: ExtensionContext) {
    locations.stop()
  }
}

class LocationsMockServer : WireMockServer(WIREMOCK_PORT) {
  companion object {
    private const val WIREMOCK_PORT = 8093
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

  fun stubGetLocation(id: String) {
    stubFor(
      get(urlPathEqualTo("/locations/$id")).willReturn(
        aResponse()
          .withHeader("Content-Type", "application/json")
          .withStatus(200)
          .withBody(
            """
              {
                "id": "$id",
                "prisonId": "MDI",
                "code": "PROP",
                "pathHierarchy": "RECP-PROP",
                "localName": "Reception Property Store",
                "locationType": "STORE"
              }
            """.trimIndent(),
          ),
      ),
    )
  }

  fun stubGetLocationNotFound(id: String) {
    stubFor(
      get(urlPathEqualTo("/locations/$id")).willReturn(
        aResponse()
          .withHeader("Content-Type", "application/json")
          .withStatus(404)
          .withBody("""{"status":404,"userMessage":"Location $id not found"}"""),
      ),
    )
  }
}
