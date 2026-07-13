package uk.gov.justice.digital.hmpps.prisonerpropertyapi.resource

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType.APPLICATION_JSON_VALUE
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.CreatePropertyLocationRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.PropertyLocationAdminDto
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.UpdatePropertyLocationRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.PropertyLocationAdminService
import uk.gov.justice.hmpps.kotlin.common.ErrorResponse
import java.util.UUID

@RestController
@Validated
@RequestMapping("/property-locations", produces = [APPLICATION_JSON_VALUE])
@PreAuthorize("hasRole('ROLE_PRISONER_PROPERTY__LOCATION_ADMIN')")
@Tag(name = "Property storage locations (management)")
@SecurityRequirement(name = "bearer-jwt")
class PropertyLocationAdminResource(
  private val propertyLocationAdminService: PropertyLocationAdminService,
) {

  @GetMapping("/prison/{prisonId}")
  @Operation(
    summary = "List the property storage locations for a prison",
    description = "Every property storage location in the prison (including full ones), each with its capacity and " +
      "how many containers it currently holds, for the management screens. Requires role ROLE_PRISONER_PROPERTY__LOCATION_ADMIN.",
    responses = [
      ApiResponse(responseCode = "200", description = "Property locations returned"),
      ApiResponse(responseCode = "401", description = "Unauthorized - a valid token was not presented", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "403", description = "Forbidden - the ROLE_PRISONER_PROPERTY__LOCATION_ADMIN role is required", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    ],
  )
  fun listPropertyLocations(
    @Parameter(description = "Prison id", example = "MDI", required = true)
    @PathVariable
    prisonId: String,
  ): List<PropertyLocationAdminDto> = propertyLocationAdminService.listPropertyLocations(prisonId)

  @PostMapping("/prison/{prisonId}")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(
    summary = "Add a new property storage location to a prison",
    description = "Creates a top-level storage location with a generated code and the given capacity. " +
      "Requires role ROLE_PRISONER_PROPERTY__LOCATION_ADMIN.",
    responses = [
      ApiResponse(responseCode = "201", description = "Property location created"),
      ApiResponse(responseCode = "400", description = "Invalid request", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "401", description = "Unauthorized - a valid token was not presented", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "403", description = "Forbidden - the ROLE_PRISONER_PROPERTY__LOCATION_ADMIN role is required", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "409", description = "A storage location with this name already exists in the prison", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    ],
  )
  fun createPropertyLocation(
    @Parameter(description = "Prison id", example = "MDI", required = true)
    @PathVariable
    prisonId: String,
    @Valid @RequestBody request: CreatePropertyLocationRequest,
  ): PropertyLocationAdminDto = propertyLocationAdminService.createPropertyLocation(prisonId, request)

  @PutMapping("/{id}")
  @Operation(
    summary = "Update a property storage location's name and/or capacity",
    description = "Requires role ROLE_PRISONER_PROPERTY__LOCATION_ADMIN.",
    responses = [
      ApiResponse(responseCode = "200", description = "Property location updated"),
      ApiResponse(responseCode = "400", description = "Invalid request", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "401", description = "Unauthorized - a valid token was not presented", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "403", description = "Forbidden - the ROLE_PRISONER_PROPERTY__LOCATION_ADMIN role is required", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "404", description = "Property location not found", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "409", description = "A storage location with this name already exists in the prison", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    ],
  )
  fun updatePropertyLocation(
    @Parameter(description = "Location id", example = "2475f250-434a-4257-afe7-b911f1773a4d", required = true)
    @PathVariable
    id: UUID,
    @Valid @RequestBody request: UpdatePropertyLocationRequest,
  ): PropertyLocationAdminDto = propertyLocationAdminService.updatePropertyLocation(id, request)

  @DeleteMapping("/{id}")
  @Operation(
    summary = "Remove a location as a place property can be stored",
    description = "Drops the location's property designation. Rejected if the location still holds any container. " +
      "Requires role ROLE_PRISONER_PROPERTY__LOCATION_ADMIN.",
    responses = [
      ApiResponse(responseCode = "200", description = "Property designation removed"),
      ApiResponse(responseCode = "401", description = "Unauthorized - a valid token was not presented", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "403", description = "Forbidden - the ROLE_PRISONER_PROPERTY__LOCATION_ADMIN role is required", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "404", description = "Property location not found", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "409", description = "The location still holds containers and cannot be removed", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    ],
  )
  fun removePropertyLocation(
    @Parameter(description = "Location id", example = "2475f250-434a-4257-afe7-b911f1773a4d", required = true)
    @PathVariable
    id: UUID,
  ): PropertyLocationAdminDto = propertyLocationAdminService.removePropertyLocation(id)
}
