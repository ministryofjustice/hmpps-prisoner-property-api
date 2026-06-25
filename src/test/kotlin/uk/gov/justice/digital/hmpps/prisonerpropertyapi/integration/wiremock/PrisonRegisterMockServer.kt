package uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.wiremock

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext

class PrisonRegisterApiExtension :
  BeforeAllCallback,
  AfterAllCallback,
  BeforeEachCallback {
  companion object {
    @JvmField
    val prisonRegister = PrisonRegisterMockServer()
  }

  override fun beforeAll(context: ExtensionContext) {
    prisonRegister.start()
  }

  override fun beforeEach(context: ExtensionContext) {
    prisonRegister.resetAll()
  }

  override fun afterAll(context: ExtensionContext) {
    prisonRegister.stop()
  }
}

class PrisonRegisterMockServer : WireMockServer(WIREMOCK_PORT) {
  companion object {
    private const val WIREMOCK_PORT = 8092
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

  fun stubGetPrisons() {
    stubFor(
      get(urlPathEqualTo("/prisons")).willReturn(
        aResponse()
          .withHeader("Content-Type", "application/json")
          .withStatus(200)
          .withBody(
            """
              [
                { "prisonId": "MDI", "prisonName": "Moorland (HMP & YOI)", "active": true },
                { "prisonId": "LEI", "prisonName": "Leeds (HMP)", "active": true }
              ]
            """.trimIndent(),
          ),
      ),
    )
  }
}
