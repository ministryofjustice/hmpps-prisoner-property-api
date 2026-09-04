package uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration

import com.microsoft.applicationinsights.TelemetryClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.http.HttpHeaders
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.reactive.server.WebTestClient
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.config.LocalStackContainer
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.config.LocalStackContainer.setLocalStackProperties
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.config.PostgresContainer
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.event.DomainEventPublisher
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.event.HmppsDomainEvent
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
import java.util.UUID

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

  /**
   * Spied rather than mocked so publishing still happens; every test can then assert what was raised.
   * Reset between tests by the bean override, so [publishedEvents] only ever sees the current test's events.
   */
  @MockitoSpyBean
  protected lateinit var domainEventPublisher: DomainEventPublisher

  /** Every domain event published so far in this test, in the order it was published. */
  protected fun publishedEvents(): List<HmppsDomainEvent> = argumentCaptor<HmppsDomainEvent>()
    .apply { verify(domainEventPublisher, atLeast(0)).publish(capture()) }
    .allValues

  /**
   * The App Insights client, mocked so tests can assert what was tracked.
   *
   * Mocked rather than spied: without the java agent the real client is an inert no-op anyway, so there
   * is no behaviour to preserve - only the calls matter.
   */
  @MockitoBean
  protected lateinit var telemetryClient: TelemetryClient

  /** The properties of the single custom event tracked under [name]. Fails if it was not tracked exactly once. */
  protected fun trackedEvent(name: String): Map<String, String> {
    val properties = argumentCaptor<Map<String, String>>()
    verify(telemetryClient).trackEvent(eq(name), properties.capture(), isNull())
    return properties.firstValue
  }

  /** Assert a custom event was never tracked - the other half of every no-change assertion. */
  protected fun assertNotTracked(name: String) {
    verify(telemetryClient, never()).trackEvent(eq(name), any(), isNull())
  }

  /**
   * Assert nothing was published. Worth stating explicitly on every no-op path: a write that turns out to
   * change nothing must stay silent, and a spurious event is invisible to a test that only checks the
   * database.
   */
  protected fun assertNoEventsPublished() {
    assertThat(publishedEvents()).isEmpty()
  }

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

  /** The events published for one container, so a multi-container write can be asserted per container. */
  protected fun publishedEventsFor(containerId: UUID): List<HmppsDomainEvent> = publishedEvents().filter { it.dpsId == containerId.toString() }

  protected fun stubPingWithResponse(status: Int) {
    hmppsAuth.stubHealthPing(status)
    prisonerSearch.stubHealthPing(status)
    prisonRegister.stubHealthPing(status)
    locations.stubHealthPing(status)
    prisonApi.stubHealthPing(status)
  }
}

/** The container the event is about. */
internal val HmppsDomainEvent.dpsId: String? get() = additionalInformation?.get("dpsId") as String?

/** Empty rather than null on a `.created` event, which omits the key entirely. */
@Suppress("UNCHECKED_CAST")
internal val HmppsDomainEvent.changedFields: List<String> get() = additionalInformation?.get("changedFields") as? List<String> ?: emptyList()

internal val HmppsDomainEvent.nomisPropertyContainerId: Long? get() = additionalInformation?.get("nomisPropertyContainerId") as Long?
