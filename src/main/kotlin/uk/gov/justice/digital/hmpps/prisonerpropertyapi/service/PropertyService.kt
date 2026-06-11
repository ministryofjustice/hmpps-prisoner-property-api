package uk.gov.justice.digital.hmpps.prisonerpropertyapi.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyItem
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyItemRepository
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyStatus
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.CreatePropertyItemRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.PropertyItemDto
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.event.DomainEventPublisher
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.event.HmppsDomainEvent
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.event.PersonReference
import java.time.LocalDateTime
import java.util.UUID

@Service
class PropertyService(
  private val repository: PropertyItemRepository,
  private val domainEventPublisher: DomainEventPublisher,
) {

  @Transactional
  fun createItem(prisonerNumber: String, request: CreatePropertyItemRequest): PropertyItemDto {
    val saved = repository.save(
      PropertyItem(
        prisonerNumber = prisonerNumber,
        description = request.description,
        location = request.location,
      ),
    )

    domainEventPublisher.publish(
      HmppsDomainEvent(
        eventType = "prison-property.item.created",
        description = "An item of prisoner property was recorded",
        personReference = PersonReference.withPrisonerNumber(prisonerNumber),
        additionalInformation = mapOf("propertyItemId" to saved.id.toString()),
      ),
    )

    return PropertyItemDto.from(saved)
  }

  @Transactional(readOnly = true)
  fun listItemsForPrisoner(prisonerNumber: String): List<PropertyItemDto> = repository.findByPrisonerNumberOrderByCreatedAtDesc(prisonerNumber).map(PropertyItemDto::from)

  @Transactional(readOnly = true)
  fun getItem(id: UUID): PropertyItemDto = repository.findById(id)
    .map(PropertyItemDto::from)
    .orElseThrow { NoSuchElementException("Property item $id not found") }

  /** Marks all held items for a prisoner as returned (e.g. on release). */
  @Transactional
  fun markAllReturnedForPrisoner(prisonerNumber: String) {
    repository.findByPrisonerNumberOrderByCreatedAtDesc(prisonerNumber)
      .filter { it.status == PropertyStatus.HELD }
      .forEach {
        it.status = PropertyStatus.RETURNED
        it.updatedAt = LocalDateTime.now()
      }
  }
}
