package uk.gov.justice.digital.hmpps.prisonerpropertyapi.config

import org.springframework.boot.actuate.info.Info
import org.springframework.boot.actuate.info.InfoContributor
import org.springframework.stereotype.Component
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.ActiveAgenciesService

/**
 * Adds `activeAgencies` (the prisons the property service is switched on for) to the actuator
 * `/info` payload, in the standard HMPPS shape the DPS frontend components and service catalogue read.
 */
@Component
class ActiveAgenciesInfo(
  private val activeAgenciesService: ActiveAgenciesService,
) : InfoContributor {
  override fun contribute(builder: Info.Builder) {
    builder.withDetail("activeAgencies", activeAgenciesService.getActiveAgencies())
  }
}
