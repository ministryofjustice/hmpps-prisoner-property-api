package uk.gov.justice.digital.hmpps.prisonerpropertyapi.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainerRepository
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.PropertyContainerDto
import java.util.UUID

@Service
class PropertyContainerService(
  private val repository: PropertyContainerRepository,
) {

  @Transactional(readOnly = true)
  fun getByPrisonerNumber(prisonerNumber: String): List<PropertyContainerDto> = repository.findByPrisonerNumberAndArchivedFalse(prisonerNumber).map(PropertyContainerDto::from)

  @Transactional(readOnly = true)
  fun getByPrisonId(prisonId: String): List<PropertyContainerDto> = repository.findByPrisonIdAndArchivedFalse(prisonId).map(PropertyContainerDto::from)

  @Transactional(readOnly = true)
  fun getById(id: UUID): PropertyContainerDto = repository.findById(id)
    .map(PropertyContainerDto::from)
    .orElseThrow { PropertyContainerNotFoundException(id) }
}
