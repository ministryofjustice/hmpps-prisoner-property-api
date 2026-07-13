package uk.gov.justice.digital.hmpps.prisonerpropertyapi.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.client.PrisonRegisterClient
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ActiveAgency
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ActiveAgencyRepository
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.AgencyStatusDto
import java.time.LocalDateTime
import kotlin.jvm.optionals.getOrNull

/**
 * The set of agencies (prisons) the property service is switched on for. Read live rather than cached: it
 * is a tiny, indexed table, and a per-pod cache made an admin's on/off toggle appear to flip-flop as
 * requests (e.g. the `/info` poll the frontend reads) were served by different pods.
 */
@Service
class ActiveAgenciesService(
  private val repository: ActiveAgencyRepository,
  private val prisonRegisterClient: PrisonRegisterClient,
) {

  fun getActiveAgencies(): List<String> = repository.findAllByActiveTrue().map { it.agencyId }.sorted()

  /**
   * The operational prisons (from prison-register) with whether the property service is active for
   * each - for the rollout admin console. Closed/non-operational agencies are filtered out, but any
   * prison already switched on stays listed even if it later drops out of the active set, so it can
   * still be switched off.
   */
  fun getAllAgencies(): List<AgencyStatusDto> {
    val active = getActiveAgencies().toSet()
    val names = prisonRegisterClient.getPrisonNames()
    return (prisonRegisterClient.getActivePrisonIds() + active)
      .map { id -> AgencyStatusDto(agencyId = id, name = names[id] ?: id, active = id in active) }
      .sortedBy { it.name }
  }

  fun isActive(agencyId: String): Boolean = repository.findById(agencyId).getOrNull()?.active == true

  @Transactional
  fun setActive(agencyId: String, active: Boolean, username: String): AgencyStatusDto {
    val agency = repository.findById(agencyId).getOrNull()
      ?.apply {
        this.active = active
        this.updatedAt = LocalDateTime.now()
        this.updatedBy = username
      }
      ?: ActiveAgency(agencyId = agencyId, active = active, updatedAt = LocalDateTime.now(), updatedBy = username)
    val saved = repository.save(agency)
    val name = prisonRegisterClient.getPrisonNames()[saved.agencyId] ?: saved.agencyId
    return AgencyStatusDto(agencyId = saved.agencyId, name = name, active = saved.active)
  }
}
