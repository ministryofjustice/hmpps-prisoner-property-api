package uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.wiremock

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.delete
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.put
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

  /**
   * Stub the single property-location lookup, returning a property location with [propertyCapacity]. Use
   * [stubGetPropertyLocationNotFound] for the case where the location is unknown or cannot store property
   * (locations-inside-prison returns 404 for both).
   */
  fun stubGetPropertyLocation(id: String, locationType: String = "BOX", propertyCapacity: Int = 100) {
    stubFor(
      get(urlPathEqualTo("/locations/property/$id")).willReturn(
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
                "locationType": "$locationType",
                "capacity": $propertyCapacity
              }
            """.trimIndent(),
          ),
      ),
    )
  }

  fun stubGetPropertyLocationNotFound(id: String) {
    stubFor(
      get(urlPathEqualTo("/locations/property/$id")).willReturn(
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

  /**
   * Stub the property-locations lookup for a prison. Each location is a Triple of (id, code, localName);
   * pathHierarchy is set to the code for simplicity, and every location is given the same [capacity].
   */
  fun stubGetBoxLocations(prisonId: String, boxes: List<Triple<String, String, String>>, capacity: Int = 10) {
    val body = boxes.joinToString(prefix = "[", postfix = "]") { (id, code, localName) ->
      """
        {
          "id": "$id",
          "prisonId": "$prisonId",
          "code": "$code",
          "pathHierarchy": "$code",
          "localName": "$localName",
          "locationType": "BOX",
          "capacity": $capacity
        }
      """.trimIndent()
    }
    stubFor(
      get(urlPathEqualTo("/locations/prison/$prisonId/property")).willReturn(
        aResponse()
          .withHeader("Content-Type", "application/json")
          .withStatus(200)
          .withBody(body),
      ),
    )
  }

  private fun propertyLocationBody(id: String, prisonId: String, code: String, localName: String, capacity: Int) = """
    {
      "id": "$id",
      "prisonId": "$prisonId",
      "code": "$code",
      "pathHierarchy": "$code",
      "localName": "$localName",
      "locationType": "BOX",
      "capacity": $capacity
    }
  """.trimIndent()

  /** Stub the create-property-location endpoint to return a created location. */
  fun stubCreatePropertyLocation(prisonId: String, id: String, code: String = "PROP1", localName: String = "Reception Store", capacity: Int = 10) {
    stubFor(
      post(urlPathEqualTo("/locations/prison/$prisonId/property")).willReturn(
        aResponse()
          .withHeader("Content-Type", "application/json")
          .withStatus(201)
          .withBody(propertyLocationBody(id, prisonId, code, localName, capacity)),
      ),
    )
  }

  /** Stub the create endpoint to return a conflict (duplicate local name). */
  fun stubCreatePropertyLocationConflict(prisonId: String) {
    stubFor(
      post(urlPathEqualTo("/locations/prison/$prisonId/property")).willReturn(
        aResponse()
          .withHeader("Content-Type", "application/json")
          .withStatus(409)
          .withBody("""{"status":409,"userMessage":"Local name already exists"}"""),
      ),
    )
  }

  /** Stub the update-property-location endpoint to return the updated location. */
  fun stubUpdatePropertyLocation(id: String, prisonId: String = "MDI", code: String = "PROP1", localName: String = "Reception Store", capacity: Int = 25) {
    stubFor(
      put(urlPathEqualTo("/locations/property/$id")).willReturn(
        aResponse()
          .withHeader("Content-Type", "application/json")
          .withStatus(200)
          .withBody(propertyLocationBody(id, prisonId, code, localName, capacity)),
      ),
    )
  }

  fun stubUpdatePropertyLocationNotFound(id: String) {
    stubFor(
      put(urlPathEqualTo("/locations/property/$id")).willReturn(
        aResponse()
          .withHeader("Content-Type", "application/json")
          .withStatus(404)
          .withBody("""{"status":404,"userMessage":"Location $id not found"}"""),
      ),
    )
  }

  /** Stub the remove-property-designation endpoint to return the location it was removed from. */
  fun stubRemovePropertyLocation(id: String, prisonId: String = "MDI", code: String = "PROP1", localName: String = "Reception Store", capacity: Int = 10) {
    stubFor(
      delete(urlPathEqualTo("/locations/property/$id")).willReturn(
        aResponse()
          .withHeader("Content-Type", "application/json")
          .withStatus(200)
          .withBody(propertyLocationBody(id, prisonId, code, localName, capacity)),
      ),
    )
  }
}
