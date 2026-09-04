package uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.sar

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainerRepository
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.sar.SarPropertyContainer
import uk.gov.justice.hmpps.kotlin.sar.HmppsPrisonSubjectAccessRequestService
import uk.gov.justice.hmpps.kotlin.sar.HmppsSubjectAccessRequestContent
import java.time.LocalDate

/**
 * Serves everything this service holds about one prisoner for a subject access request.
 *
 * Declaring this bean is the whole wiring: hmpps-kotlin-spring-boot-starter registers
 * `GET /subject-access-request` conditionally on a [uk.gov.justice.hmpps.kotlin.sar.HmppsSubjectAccessRequestService]
 * being present, and guards it with the SAR_DATA_ACCESS role. There is no controller to write.
 *
 * It reads the containers and their events directly rather than going through
 * [uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.PropertyContainerService]: the product read paths
 * enrich what they return by calling prisoner-search, prison-register, locations-inside-prison and prison-api,
 * and a SAR response must contain only data this product owns.
 */
@Service
class PrisonerPropertySubjectAccessRequestService(
  private val propertyContainerRepository: PropertyContainerRepository,
) : HmppsPrisonSubjectAccessRequestService {

  /**
   * [fromDate] and [toDate] are both inclusive dates; [toDate] is widened to the start of the following day so
   * that everything recorded on that date is caught whatever the time of day.
   *
   * Returns null when the prisoner has no property, which the library controller turns into a 204 - an empty
   * list would be reported as "we hold this about you: nothing", which is not the same statement.
   */
  @Transactional(readOnly = true)
  override fun getPrisonContentFor(
    prn: String,
    fromDate: LocalDate?,
    toDate: LocalDate?,
  ): HmppsSubjectAccessRequestContent? {
    val containers = propertyContainerRepository
      .findSarContent(prn, fromDate?.atStartOfDay(), toDate?.plusDays(1)?.atStartOfDay())
      .map { SarPropertyContainer.from(it) }
      .sortedByDescending { it.createdDateTime }

    return if (containers.isEmpty()) null else HmppsSubjectAccessRequestContent(content = containers)
  }
}
