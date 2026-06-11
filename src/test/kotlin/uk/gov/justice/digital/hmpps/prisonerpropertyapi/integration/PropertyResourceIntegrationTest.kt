package uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyItemRepository

class PropertyResourceIntegrationTest : IntegrationTestBase() {

  @Autowired
  private lateinit var repository: PropertyItemRepository

  @AfterEach
  fun cleanUp() = repository.deleteAll()

  @Test
  fun `requires authentication`() {
    webTestClient.get().uri("/prisoners/A1234BC/property")
      .exchange()
      .expectStatus().isUnauthorized
  }

  @Test
  fun `requires the correct role to write`() {
    webTestClient.post().uri("/prisoners/A1234BC/property")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .contentType(MediaType.APPLICATION_JSON)
      .bodyValue(mapOf("description" to "Wallet", "location" to "RECEPTION"))
      .exchange()
      .expectStatus().isForbidden
  }

  @Test
  fun `can create and list property for a prisoner`() {
    webTestClient.post().uri("/prisoners/A1234BC/property")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RW")))
      .contentType(MediaType.APPLICATION_JSON)
      .bodyValue(mapOf("description" to "Black leather wallet", "location" to "RECEPTION"))
      .exchange()
      .expectStatus().isCreated
      .expectBody()
      .jsonPath("$.prisonerNumber").isEqualTo("A1234BC")
      .jsonPath("$.status").isEqualTo("HELD")
      .jsonPath("$.id").exists()

    webTestClient.get().uri("/prisoners/A1234BC/property")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.length()").isEqualTo(1)
      .jsonPath("$[0].description").isEqualTo("Black leather wallet")

    assertThat(repository.findAll()).hasSize(1)
  }
}
