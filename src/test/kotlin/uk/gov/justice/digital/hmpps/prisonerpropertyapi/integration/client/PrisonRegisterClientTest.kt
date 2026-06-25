package uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.client

import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.cache.CacheManager
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.client.PrisonRegisterClient
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.config.CacheConfiguration.Companion.PRISON_NAMES_CACHE_NAME
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.wiremock.PrisonRegisterApiExtension.Companion.prisonRegister

class PrisonRegisterClientTest : IntegrationTestBase() {

  @Autowired
  private lateinit var prisonRegisterClient: PrisonRegisterClient

  @Autowired
  private lateinit var cacheManager: CacheManager

  @BeforeEach
  fun clearCache() {
    cacheManager.getCache(PRISON_NAMES_CACHE_NAME)?.clear()
  }

  @Test
  fun `returns a map of prison id to name`() {
    prisonRegister.stubGetPrisons()

    val names = prisonRegisterClient.getPrisonNames()

    assertThat(names).containsEntry("MDI", "Moorland (HMP & YOI)")
    assertThat(names).containsEntry("LEI", "Leeds (HMP)")
  }

  @Test
  fun `caches the prison list so a second call does not hit the API again`() {
    prisonRegister.stubGetPrisons()

    prisonRegisterClient.getPrisonNames()
    prisonRegisterClient.getPrisonNames()

    prisonRegister.verify(1, getRequestedFor(urlPathEqualTo("/prisons")))
  }
}
