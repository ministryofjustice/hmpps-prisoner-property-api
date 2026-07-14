package uk.gov.justice.digital.hmpps.prisonerpropertyapi.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager
import org.springframework.web.reactive.function.client.WebClient
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.SYSTEM_USERNAME
import uk.gov.justice.hmpps.kotlin.auth.authorisedWebClient
import uk.gov.justice.hmpps.kotlin.auth.healthWebClient
import java.time.Duration

@Configuration
class WebClientConfiguration(
  @param:Value("\${hmpps-auth.url}") val hmppsAuthBaseUri: String,
  @param:Value("\${api.base.url.prisoner-search}") val prisonerSearchBaseUri: String,
  @param:Value("\${api.base.url.prison-register}") val prisonRegisterBaseUri: String,
  @param:Value("\${api.base.url.locations-inside-prison}") val locationsBaseUri: String,
  @param:Value("\${api.base.url.prison-api}") val prisonApiBaseUri: String,
  @param:Value("\${api.health-timeout:2s}") val healthTimeout: Duration,
  @param:Value("\${api.timeout:20s}") val timeout: Duration,
) {
  // HMPPS Auth health ping is required if your service calls HMPPS Auth to get a token to call other services
  @Bean
  fun hmppsAuthHealthWebClient(builder: WebClient.Builder): WebClient = builder.healthWebClient(hmppsAuthBaseUri, healthTimeout)

  // Health-ping web clients
  @Bean
  fun prisonerSearchHealthWebClient(builder: WebClient.Builder): WebClient = builder.healthWebClient(prisonerSearchBaseUri, healthTimeout)

  // prison-register's /prisons endpoint is unauthenticated, so the same client serves data calls and the health ping
  @Bean
  fun prisonRegisterWebClient(builder: WebClient.Builder): WebClient = builder.healthWebClient(prisonRegisterBaseUri, healthTimeout)

  @Bean
  fun locationsHealthWebClient(builder: WebClient.Builder): WebClient = builder.healthWebClient(locationsBaseUri, healthTimeout)

  @Bean
  fun prisonApiHealthWebClient(builder: WebClient.Builder): WebClient = builder.healthWebClient(prisonApiBaseUri, healthTimeout)

  // OAuth2 authorised web clients (system token via client_credentials)
  @Bean
  fun prisonerSearchWebClient(
    authorizedClientManager: OAuth2AuthorizedClientManager,
    builder: WebClient.Builder,
  ): WebClient = builder.authorisedWebClient(
    authorizedClientManager,
    registrationId = SYSTEM_USERNAME,
    url = prisonerSearchBaseUri,
    timeout,
  )

  @Bean
  fun locationsWebClient(
    authorizedClientManager: OAuth2AuthorizedClientManager,
    builder: WebClient.Builder,
  ): WebClient = builder.authorisedWebClient(
    authorizedClientManager,
    registrationId = SYSTEM_USERNAME,
    url = locationsBaseUri,
    timeout,
  )

  @Bean
  fun prisonApiWebClient(
    authorizedClientManager: OAuth2AuthorizedClientManager,
    builder: WebClient.Builder,
  ): WebClient = builder.authorisedWebClient(
    authorizedClientManager,
    registrationId = SYSTEM_USERNAME,
    url = prisonApiBaseUri,
    timeout,
  )
}
