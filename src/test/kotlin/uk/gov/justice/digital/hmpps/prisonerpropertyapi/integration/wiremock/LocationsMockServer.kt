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

  fun stubGetLocation(id: String, locationType: String = "BOX") {
    stubFor(
      get(urlPathEqualTo("/locations/non-residential/$id")).willReturn(
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
                "locationType": "$locationType"
              }
            """.trimIndent(),
          ),
      ),
    )
  }

  fun stubGetLocationNotFound(id: String) {
    stubFor(
      get(urlPathEqualTo("/locations/non-residential/$id")).willReturn(
        aResponse()
          .withHeader("Content-Type", "application/json")
          .withStatus(404)
          .withBody("""{"status":404,"userMessage":"Location $id not found"}"""),
      ),
    )
  }

  /** Stub the batch lookup, returning one non-residential location object per supplied id. */
  fun stubPostLocationsBatch(vararg ids: String) {
    val body = ids.joinToString(prefix = "[", postfix = "]") { id ->
      """
        {
          "id": "$id",
          "prisonId": "MDI",
          "code": "PROP",
          "pathHierarchy": "RECP-PROP",
          "localName": "Reception Property Store",
          "locationType": "STORE",
          "status": "ACTIVE"
        }
      """.trimIndent()
    }
    stubFor(
      post(urlPathEqualTo("/locations/non-residential/batch")).willReturn(
        aResponse()
          .withHeader("Content-Type", "application/json")
          .withStatus(200)
          .withBody(body),
      ),
    )
  }

  /**
   * Stub the per-prison non-residential locations lookup (used to resolve a searched storage-location code),
   * returning one BOX location per supplied (code to id) pair.
   */
  fun stubGetNonResidentialLocations(prisonId: String, vararg codesToIds: Pair<String, String>) {
    val body = codesToIds.joinToString(prefix = "[", postfix = "]") { (code, id) ->
      """
        {
          "id": "$id",
          "prisonId": "$prisonId",
          "code": "$code",
          "pathHierarchy": "PROP-$code",
          "localName": "Property box $code",
          "locationType": "BOX"
        }
      """.trimIndent()
    }
    stubFor(
      get(urlPathEqualTo("/locations/prison/$prisonId/non-residential")).willReturn(
        aResponse()
          .withHeader("Content-Type", "application/json")
          .withStatus(200)
          .withBody(body),
      ),
    )
  }

  /** Stub the batch lookup to fail, to exercise graceful degradation. */
  fun stubPostLocationsBatchError(status: Int = 500) {
    stubFor(
      post(urlPathEqualTo("/locations/non-residential/batch")).willReturn(
        aResponse()
          .withHeader("Content-Type", "application/json")
          .withStatus(status)
          .withBody("""{"status":$status,"userMessage":"error"}"""),
      ),
    )
  }
}
