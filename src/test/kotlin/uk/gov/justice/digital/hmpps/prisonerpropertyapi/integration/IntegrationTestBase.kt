package uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration

import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.http.HttpHeaders
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.config.LocalStackContainer
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.config.LocalStackContainer.setLocalStackProperties
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.config.PostgresContainer
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.wiremock.HmppsAuthApiExtension
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.wiremock.HmppsAuthApiExtension.Companion.hmppsAuth
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.wiremock.LocationsApiExtension
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.wiremock.LocationsApiExtension.Companion.locations
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.wiremock.PrisonApiExtension
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.wiremock.PrisonApiExtension.Companion.prisonApi
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.wiremock.PrisonRegisterApiExtension
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.wiremock.PrisonRegisterApiExtension.Companion.prisonRegister
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.wiremock.PrisonerSearchApiExtension
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.wiremock.PrisonerSearchApiExtension.Companion.prisonerSearch
import uk.gov.justice.hmpps.test.kotlin.auth.JwtAuthorisationHelper

@ExtendWith(
  HmppsAuthApiExtension::class,
  PrisonerSearchApiExtension::class,
  PrisonRegisterApiExtension::class,
  LocationsApiExtension::class,
  PrisonApiExtension::class,
)
@SpringBootTest(webEnvironment = RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureWebTestClient
abstract class IntegrationTestBase {

  @Autowired
  protected lateinit var webTestClient: WebTestClient

  @Autowired
  protected lateinit var jwtAuthHelper: JwtAuthorisationHelper

  companion object {
    private val pgContainer = PostgresContainer.instance
    private val localStackContainer = LocalStackContainer.instance

    @JvmStatic
    @DynamicPropertySource
    fun properties(registry: DynamicPropertyRegistry) {
      pgContainer?.run {
        registry.add("spring.datasource.url", ::getJdbcUrl)
        registry.add("spring.datasource.username", ::getUsername)
        registry.add("spring.datasource.password", ::getPassword)
        registry.add("spring.flyway.url", ::getJdbcUrl)
        registry.add("spring.flyway.user", ::getUsername)
        registry.add("spring.flyway.password", ::getPassword)
      }
      localStackContainer?.also { setLocalStackProperties(it, registry) }
    }
  }

  internal fun setAuthorisation(
    username: String? = "AUTH_ADM",
    roles: List<String> = listOf(),
    scopes: List<String> = listOf("read"),
  ): (HttpHeaders) -> Unit = jwtAuthHelper.setAuthorisationHeader(username = username, scope = scopes, roles = roles)

  protected fun stubPingWithResponse(status: Int) {
    hmppsAuth.stubHealthPing(status)
    prisonerSearch.stubHealthPing(status)
    prisonRegister.stubHealthPing(status)
    locations.stubHealthPing(status)
    prisonApi.stubHealthPing(status)
  }
}
