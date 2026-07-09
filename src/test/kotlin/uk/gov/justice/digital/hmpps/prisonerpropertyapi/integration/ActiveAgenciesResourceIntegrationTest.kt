package uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.cache.CacheManager
import org.springframework.http.MediaType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ActiveAgencyRepository
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.wiremock.PrisonRegisterApiExtension.Companion.prisonRegister

class ActiveAgenciesResourceIntegrationTest : IntegrationTestBase() {

  @Autowired
  private lateinit var repository: ActiveAgencyRepository

  @Autowired
  private lateinit var cacheManager: CacheManager

  @BeforeEach
  fun setUp() {
    repository.deleteAll()
    cacheManager.cacheNames.forEach { cacheManager.getCache(it)?.clear() }
    // The toggle (PUT) and the /all list both resolve prison names from prison-register.
    prisonRegister.stubGetPrisons()
  }

  private fun setActive(agencyId: String, active: Boolean) = webTestClient.put()
    .uri("/active-agencies/$agencyId")
    .headers(setAuthorisation(username = "ADMIN_USER", roles = listOf("ROLE_PRISONER_PROPERTY__ADMIN")))
    .contentType(MediaType.APPLICATION_JSON)
    .bodyValue(mapOf("active" to active))
    .exchange()

  @Test
  fun `requires a token`() {
    webTestClient.get().uri("/active-agencies").exchange().expectStatus().isUnauthorized
    webTestClient.put().uri("/active-agencies/MDI")
      .contentType(MediaType.APPLICATION_JSON).bodyValue(mapOf("active" to true))
      .exchange().expectStatus().isUnauthorized
  }

  @Test
  fun `requires the admin role`() {
    webTestClient.get().uri("/active-agencies")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange().expectStatus().isForbidden

    webTestClient.put().uri("/active-agencies/MDI")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RW")))
      .contentType(MediaType.APPLICATION_JSON).bodyValue(mapOf("active" to true))
      .exchange().expectStatus().isForbidden

    webTestClient.get().uri("/active-agencies/all")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange().expectStatus().isForbidden
  }

  @Test
  fun `lists every prison with its active flag, sorted by name`() {
    setActive("MDI", true).expectStatus().isOk

    webTestClient.get().uri("/active-agencies/all")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__ADMIN")))
      .exchange().expectStatus().isOk
      .expectBody()
      .jsonPath("$[0].agencyId").isEqualTo("LEI")
      .jsonPath("$[0].name").isEqualTo("Leeds (HMP)")
      .jsonPath("$[0].active").isEqualTo(false)
      .jsonPath("$[1].agencyId").isEqualTo("MDI")
      .jsonPath("$[1].name").isEqualTo("Moorland (HMP & YOI)")
      .jsonPath("$[1].active").isEqualTo(true)
  }

  @Test
  fun `activating an agency lists it and publishes it under info`() {
    setActive("MDI", true)
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.agencyId").isEqualTo("MDI")
      .jsonPath("$.name").isEqualTo("Moorland (HMP & YOI)")
      .jsonPath("$.active").isEqualTo(true)

    webTestClient.get().uri("/active-agencies")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__ADMIN")))
      .exchange().expectStatus().isOk
      .expectBody().jsonPath("$").isEqualTo(listOf("MDI"))

    webTestClient.get().uri("/info")
      .exchange().expectStatus().isOk
      .expectBody().jsonPath("$.activeAgencies").isEqualTo(listOf("MDI"))
  }

  @Test
  fun `deactivating an agency removes it from the active list but leaves the others`() {
    setActive("MDI", true).expectStatus().isOk
    setActive("LEI", true).expectStatus().isOk

    setActive("MDI", false)
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.agencyId").isEqualTo("MDI")
      .jsonPath("$.active").isEqualTo(false)

    webTestClient.get().uri("/active-agencies")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__ADMIN")))
      .exchange().expectStatus().isOk
      .expectBody().jsonPath("$").isEqualTo(listOf("LEI"))

    webTestClient.get().uri("/info")
      .exchange().expectStatus().isOk
      .expectBody().jsonPath("$.activeAgencies").isEqualTo(listOf("LEI"))
  }
}
