package uk.gov.justice.digital.hmpps.prisonerpropertyapi

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * OAuth2 client-credentials registration id used when this service calls other HMPPS APIs.
 * Must match the `spring.security.oauth2.client.registration.*` key in application.yml.
 */
const val SYSTEM_USERNAME = "prisoner-property-api"

@SpringBootApplication
class PrisonerPropertyApi

fun main(args: Array<String>) {
  runApplication<PrisonerPropertyApi>(*args)
}
