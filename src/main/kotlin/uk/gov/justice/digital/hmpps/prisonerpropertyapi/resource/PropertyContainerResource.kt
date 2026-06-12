package uk.gov.justice.digital.hmpps.prisonerpropertyapi.resource

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Pattern
import org.springframework.http.MediaType.APPLICATION_JSON_VALUE
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.PropertyContainerDto
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.PropertyContainerService
import uk.gov.justice.hmpps.kotlin.common.ErrorResponse
import java.util.UUID

@RestController
@Validated
@RequestMapping("/property-containers", produces = [APPLICATION_JSON_VALUE])
@PreAuthorize("hasRole('ROLE_PRISONER_PROPERTY__RO')")
@Tag(name = "Property containers")
@SecurityRequirement(name = "bearer-jwt")
class PropertyContainerResource(
  private val propertyContainerService: PropertyContainerService,
) {

  @GetMapping("/prisoner/{prisonerNumber}")
  @Operation(
    summary = "Get the property containers held for a prisoner",
    description = "Requires role ROLE_PRISONER_PROPERTY__RO. Returns an empty list if the prisoner has no property containers.",
    responses = [
      ApiResponse(responseCode = "200", description = "Property containers returned"),
      ApiResponse(responseCode = "400", description = "Invalid prisoner number", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "401", description = "Unauthorized - a valid token was not presented", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "403", description = "Forbidden - the ROLE_PRISONER_PROPERTY__RO role is required", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    ],
  )
  fun getByPrisonerNumber(
    @Parameter(description = "Prisoner (NOMS) number", example = "A1234BC", required = true)
    @Pattern(regexp = "([a-zA-Z][0-9]{4}[a-zA-Z]{2})", message = "Prisoner number must be in the format A1234BC")
    @PathVariable
    prisonerNumber: String,
  ): List<PropertyContainerDto> = propertyContainerService.getByPrisonerNumber(prisonerNumber)

  @GetMapping("/prison/{prisonId}")
  @Operation(
    summary = "Get the property containers held in a prison",
    description = "Requires role ROLE_PRISONER_PROPERTY__RO. Returns an empty list if the prison has no property containers.",
    responses = [
      ApiResponse(responseCode = "200", description = "Property containers returned"),
      ApiResponse(responseCode = "400", description = "Invalid prison id", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "401", description = "Unauthorized - a valid token was not presented", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "403", description = "Forbidden - the ROLE_PRISONER_PROPERTY__RO role is required", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    ],
  )
  fun getByPrisonId(
    @Parameter(description = "Id of the prison", example = "LEI", required = true)
    @Pattern(regexp = "^[A-Z]{2}I|ZZGHI$", message = "Prison id must be 3 characters ending in an I, or ZZGHI")
    @PathVariable
    prisonId: String,
  ): List<PropertyContainerDto> = propertyContainerService.getByPrisonId(prisonId)

  @GetMapping("/{id}")
  @Operation(
    summary = "Get a single property container by its id",
    description = "Requires role ROLE_PRISONER_PROPERTY__RO.",
    responses = [
      ApiResponse(responseCode = "200", description = "Property container returned"),
      ApiResponse(responseCode = "401", description = "Unauthorized - a valid token was not presented", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "403", description = "Forbidden - the ROLE_PRISONER_PROPERTY__RO role is required", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "404", description = "Property container not found", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    ],
  )
  fun getById(
    @Parameter(description = "Property container id", example = "0196f1d3-9a1f-7c3a-9b2e-2c1f3a4b5c6d", required = true)
    @PathVariable
    id: UUID,
  ): PropertyContainerDto = propertyContainerService.getById(id)
}
