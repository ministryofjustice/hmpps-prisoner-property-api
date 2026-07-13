package uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.cache.CacheManager
import org.springframework.http.MediaType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainer
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainerRepository
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEvent
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEventType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.wiremock.HmppsAuthApiExtension.Companion.hmppsAuth
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.wiremock.LocationsApiExtension.Companion.locations
import java.time.LocalDateTime
import java.util.UUID

class PropertyLocationAdminResourceIntegrationTest : IntegrationTestBase() {

  @Autowired
  private lateinit var repository: PropertyContainerRepository

  @Autowired
  private lateinit var cacheManager: CacheManager

  @BeforeEach
  fun setUp() {
    cacheManager.cacheNames.forEach { cacheManager.getCache(it)?.clear() }
  }

  @AfterEach
  fun cleanUp() = repository.deleteAll()

  private val adminRole = listOf("ROLE_PRISONER_PROPERTY__LOCATION_ADMIN")

  @Test
  fun `list requires the LOCATION_ADMIN role`() {
    webTestClient.get().uri("/property-locations/prison/LEI")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange()
      .expectStatus().isForbidden
  }

  @Test
  fun `lists property locations with capacity and how full they are`() {
    repository.save(seedContainerAt(LOCATION_B, "LEI"))
    locations.stubGetBoxLocations("LEI", listOf(Triple(LOCATION_B.toString(), "PB5638", "Reception Property Store")), capacity = 10)

    webTestClient.get().uri("/property-locations/prison/LEI")
      .headers(setAuthorisation(roles = adminRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.length()").isEqualTo(1)
      .jsonPath("$[0].id").isEqualTo(LOCATION_B.toString())
      .jsonPath("$[0].name").isEqualTo("Reception Property Store")
      .jsonPath("$[0].capacity").isEqualTo(10)
      .jsonPath("$[0].containersHeld").isEqualTo(1)
      .jsonPath("$[0].availableSpaces").isEqualTo(9)
  }

  @Test
  fun `creates a property location`() {
    hmppsAuth.stubGrantToken()
    locations.stubCreatePropertyLocation("LEI", id = NEW_LOCATION.toString(), code = "PROP1", localName = "Reception Store", capacity = 10)

    webTestClient.post().uri("/property-locations/prison/LEI")
      .headers(setAuthorisation(roles = adminRole))
      .contentType(MediaType.APPLICATION_JSON)
      .bodyValue("""{ "localName": "Reception Store", "capacity": 10 }""")
      .exchange()
      .expectStatus().isCreated
      .expectBody()
      .jsonPath("$.id").isEqualTo(NEW_LOCATION.toString())
      .jsonPath("$.name").isEqualTo("Reception Store")
      .jsonPath("$.capacity").isEqualTo(10)
      .jsonPath("$.containersHeld").isEqualTo(0)
      .jsonPath("$.availableSpaces").isEqualTo(10)
  }

  @Test
  fun `create returns 409 when the name already exists`() {
    hmppsAuth.stubGrantToken()
    locations.stubCreatePropertyLocationConflict("LEI")

    webTestClient.post().uri("/property-locations/prison/LEI")
      .headers(setAuthorisation(roles = adminRole))
      .contentType(MediaType.APPLICATION_JSON)
      .bodyValue("""{ "localName": "Reception Store", "capacity": 10 }""")
      .exchange()
      .expectStatus().isEqualTo(409)
  }

  @Test
  fun `create rejects a negative capacity`() {
    webTestClient.post().uri("/property-locations/prison/LEI")
      .headers(setAuthorisation(roles = adminRole))
      .contentType(MediaType.APPLICATION_JSON)
      .bodyValue("""{ "localName": "Reception Store", "capacity": -1 }""")
      .exchange()
      .expectStatus().isBadRequest
  }

  @Test
  fun `updates a property location`() {
    hmppsAuth.stubGrantToken()
    locations.stubUpdatePropertyLocation(NEW_LOCATION.toString(), prisonId = "LEI", localName = "Reception Store", capacity = 25)

    webTestClient.put().uri("/property-locations/$NEW_LOCATION")
      .headers(setAuthorisation(roles = adminRole))
      .contentType(MediaType.APPLICATION_JSON)
      .bodyValue("""{ "capacity": 25 }""")
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.capacity").isEqualTo(25)
      .jsonPath("$.containersHeld").isEqualTo(0)
  }

  @Test
  fun `update returns 404 for an unknown location`() {
    hmppsAuth.stubGrantToken()
    locations.stubUpdatePropertyLocationNotFound(NEW_LOCATION.toString())

    webTestClient.put().uri("/property-locations/$NEW_LOCATION")
      .headers(setAuthorisation(roles = adminRole))
      .contentType(MediaType.APPLICATION_JSON)
      .bodyValue("""{ "capacity": 25 }""")
      .exchange()
      .expectStatus().isNotFound
  }

  @Test
  fun `update is rejected when the capacity would drop below the containers held`() {
    // one container is stored here; the downstream update is intentionally not stubbed - a 409 proves the
    // guard rejects before any call to locations-inside-prison.
    repository.save(seedContainerAt(LOCATION_B, "LEI"))

    webTestClient.put().uri("/property-locations/$LOCATION_B")
      .headers(setAuthorisation(roles = adminRole))
      .contentType(MediaType.APPLICATION_JSON)
      .bodyValue("""{ "capacity": 0 }""")
      .exchange()
      .expectStatus().isEqualTo(409)
  }

  @Test
  fun `remove is rejected when the location still holds containers`() {
    repository.save(seedContainerAt(LOCATION_B, "LEI"))

    webTestClient.delete().uri("/property-locations/$LOCATION_B")
      .headers(setAuthorisation(roles = adminRole))
      .exchange()
      .expectStatus().isEqualTo(409)
  }

  @Test
  fun `removes an empty property location`() {
    hmppsAuth.stubGrantToken()
    locations.stubRemovePropertyLocation(EMPTY_BOX.toString(), prisonId = "LEI")

    webTestClient.delete().uri("/property-locations/$EMPTY_BOX")
      .headers(setAuthorisation(roles = adminRole))
      .exchange()
      .expectStatus().isOk
  }

  private fun seedContainerAt(locationId: UUID, prisonId: String): PropertyContainer {
    val container = PropertyContainer(
      prisonerNumber = "A1234BC",
      prisonId = prisonId,
      containerType = ContainerType.STANDARD,
      createdByUserId = "USER1",
      currentSealNumber = "SEAL-${UUID.randomUUID()}",
    )
    container.events.add(
      PropertyEvent(container, PropertyEventType.CREATED_SEALED, baseTime, "USER1", sealNumber = "SEAL001", toInternalLocationId = locationId),
    )
    container.refreshDerivedState()
    return container
  }

  private companion object {
    private val baseTime: LocalDateTime = LocalDateTime.parse("2026-01-01T09:00:00")
    private val LOCATION_B: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val EMPTY_BOX: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")
    private val NEW_LOCATION: UUID = UUID.fromString("44444444-4444-4444-4444-444444444444")
  }
}
