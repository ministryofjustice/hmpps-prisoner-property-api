package uk.gov.justice.digital.hmpps.prisonerpropertyapi.resource

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.CreatePropertyItemRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.PropertyItemDto
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.PropertyService
import java.util.UUID

@RestController
@RequestMapping(produces = ["application/json"])
@Tag(name = "Prisoner property")
@SecurityRequirement(name = "bearer-jwt")
class PropertyResource(private val propertyService: PropertyService) {

  @Operation(summary = "Record a new item of property for a prisoner")
  @PostMapping("/prisoners/{prisonerNumber}/property")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('ROLE_PRISONER_PROPERTY__RW')")
  fun createItem(
    @PathVariable prisonerNumber: String,
    @Valid @RequestBody request: CreatePropertyItemRequest,
  ): PropertyItemDto = propertyService.createItem(prisonerNumber, request)

  @Operation(summary = "List all property items held for a prisoner")
  @GetMapping("/prisoners/{prisonerNumber}/property")
  @PreAuthorize("hasAnyRole('ROLE_PRISONER_PROPERTY__RO', 'ROLE_PRISONER_PROPERTY__RW')")
  fun listItems(@PathVariable prisonerNumber: String): List<PropertyItemDto> = propertyService.listItemsForPrisoner(prisonerNumber)

  @Operation(summary = "Get a single property item by id")
  @GetMapping("/property/{id}")
  @PreAuthorize("hasAnyRole('ROLE_PRISONER_PROPERTY__RO', 'ROLE_PRISONER_PROPERTY__RW')")
  fun getItem(@PathVariable id: UUID): PropertyItemDto = propertyService.getItem(id)
}
