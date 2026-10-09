package uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType

/**
 * The template endpoint serves the mustache report template to the SAR tool, which renders it against the
 * data endpoint's response. The endpoint is the library's; what is worth asserting here is that it is
 * switched on, that the file it serves is the one we think it is, and that it is not readable without the
 * SAR role.
 */
class SubjectAccessRequestTemplateIntegrationTest : IntegrationTestBase() {

  @Test
  fun `requires a token`() {
    webTestClient.get().uri(TEMPLATE_URL)
      .exchange()
      .expectStatus().isUnauthorized
  }

  @Test
  fun `requires the SAR_DATA_ACCESS role`() {
    webTestClient.get().uri(TEMPLATE_URL)
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange()
      .expectStatus().isForbidden
  }

  @Test
  fun `serves the report template as plain text`() {
    val template = webTestClient.get().uri(TEMPLATE_URL)
      .headers(setAuthorisation(roles = listOf("ROLE_SAR_DATA_ACCESS")))
      .exchange()
      .expectStatus().isOk
      .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_PLAIN)
      .expectBody(String::class.java)
      .returnResult().responseBody!!

    assertThat(template).contains("<h1 class=\"title\">Prisoner Property</h1>")
  }

  /**
   * Every field the API returns should be somewhere in the template, and every field the template reads
   * should be one the API returns. The two drift apart silently otherwise - a field added to the response
   * and forgotten in the template is data withheld from someone's report, which is the failure this whole
   * process exists to prevent. MAPB-771 makes this exact against a rendered report; this is the cheap
   * version that runs on every build.
   *
   * The storage location ids are the deliberate exception - see the test below.
   */
  @Test
  fun `the template reads every field the response carries`() {
    val template = webTestClient.get().uri(TEMPLATE_URL)
      .headers(setAuthorisation(roles = listOf("ROLE_SAR_DATA_ACCESS")))
      .exchange()
      .expectStatus().isOk
      .expectBody(String::class.java)
      .returnResult().responseBody!!

    val containerFields = listOf(
      "containerType", "sealNumber", "status", "prisonId", "createdDateTime", "createdByUsername",
      "proposedDisposalDate", "removalOutcome", "removalDate", "events",
    )
    val eventFields = listOf("eventType", "eventDateTime", "eventDate", "eventUsername", "toStorageLocationType", "fromPrisonId", "toPrisonId", "relatedContainerSealNumber")

    assertThat(containerFields + eventFields).allSatisfy { field ->
      assertThat(template).`as`("template renders %s", field).contains(field)
    }
  }

  /**
   * The prison number is the one identifier allowed in the API response, but it must not appear in the
   * report body - the SAR tool already prints it in the header of every page.
   */
  @Test
  fun `the template does not render the prison number`() {
    val template = webTestClient.get().uri(TEMPLATE_URL)
      .headers(setAuthorisation(roles = listOf("ROLE_SAR_DATA_ACCESS")))
      .exchange()
      .expectStatus().isOk
      .expectBody(String::class.java)
      .returnResult().responseBody!!

    assertThat(template).doesNotContain("prisonerNumber")
  }

  /**
   * The Offender SAR team decided at the data review (MAPB-768) that where in the establishment property was
   * stored is not to be shown. The response still carries the ids while V1 of the template is live in preprod
   * and production, so this guards against them being rendered again.
   */
  @Test
  fun `the template does not render storage locations`() {
    val template = webTestClient.get().uri(TEMPLATE_URL)
      .headers(setAuthorisation(roles = listOf("ROLE_SAR_DATA_ACCESS")))
      .exchange()
      .expectStatus().isOk
      .expectBody(String::class.java)
      .returnResult().responseBody!!

    assertThat(template).doesNotContain("fromLocationId", "toLocationId", "getLocationNameByDpsId")
  }

  private companion object {
    const val TEMPLATE_URL = "/subject-access-request/template"
  }
}
