package uk.gov.justice.digital.hmpps.prisonerpropertyapi.resource

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Pattern
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType.APPLICATION_JSON_VALUE
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.CombineContainersRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.CreatePropertyContainerRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.DisposeContainerRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.MoveContainerRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.PropertyContainerDto
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.RemoveContainerRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.UpdatePropertyContainerRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.event.DomainEventPublisher
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.CombineResult
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.PropertyContainerService
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.PropertyContainerWriteService
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.WriteResult
import uk.gov.justice.hmpps.kotlin.auth.HmppsAuthenticationHolder
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
  private val propertyContainerWriteService: PropertyContainerWriteService,
  private val domainEventPublisher: DomainEventPublisher,
  private val authenticationHolder: HmppsAuthenticationHolder,
) {

  /** Publishes the domain event (if any) after the service transaction has committed. */
  private fun WriteResult.publishAfterCommit(): PropertyContainerDto {
    event?.let(domainEventPublisher::publish)
    return container
  }

  /** Publishes all the combine domain events after the service transaction has committed. */
  private fun CombineResult.publishAllAfterCommit(): PropertyContainerDto {
    events.forEach(domainEventPublisher::publish)
    return container
  }

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

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('ROLE_PRISONER_PROPERTY__RW')")
  @Operation(
    summary = "Create a new property container",
    description = "Requires role ROLE_PRISONER_PROPERTY__RW.",
    responses = [
      ApiResponse(responseCode = "201", description = "Property container created"),
      ApiResponse(responseCode = "400", description = "Invalid request", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "401", description = "Unauthorized - a valid token was not presented", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "403", description = "Forbidden - the ROLE_PRISONER_PROPERTY__RW role is required", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    ],
  )
  fun create(@Valid @RequestBody request: CreatePropertyContainerRequest): PropertyContainerDto = propertyContainerWriteService.create(request, currentUsername()).publishAfterCommit()

  @PutMapping("/{id}")
  @PreAuthorize("hasRole('ROLE_PRISONER_PROPERTY__RW')")
  @Operation(
    summary = "Update a property container",
    description = "Replaces the container's mutable details (type, seal, location, proposed disposal date), recording any change in history. Requires role ROLE_PRISONER_PROPERTY__RW.",
    responses = [
      ApiResponse(responseCode = "200", description = "Property container updated"),
      ApiResponse(responseCode = "400", description = "Invalid request", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "401", description = "Unauthorized - a valid token was not presented", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "403", description = "Forbidden - the ROLE_PRISONER_PROPERTY__RW role is required", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "404", description = "Property container not found", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    ],
  )
  fun update(
    @Parameter(description = "Property container id", example = "0196f1d3-9a1f-7c3a-9b2e-2c1f3a4b5c6d", required = true)
    @PathVariable
    id: UUID,
    @Valid @RequestBody request: UpdatePropertyContainerRequest,
  ): PropertyContainerDto = propertyContainerWriteService.update(id, request, currentUsername()).publishAfterCommit()

  @PostMapping("/{id}/dispose")
  @PreAuthorize("hasRole('ROLE_PRISONER_PROPERTY__RW')")
  @Operation(
    summary = "Dispose of a property container",
    description = "Records the container as disposed of (destroyed), taking it out of active storage and clearing its location. Requires role ROLE_PRISONER_PROPERTY__RW.",
    responses = [
      ApiResponse(responseCode = "200", description = "Property container disposed"),
      ApiResponse(responseCode = "400", description = "Invalid request", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "401", description = "Unauthorized - a valid token was not presented", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "403", description = "Forbidden - the ROLE_PRISONER_PROPERTY__RW role is required", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "404", description = "Property container not found", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "409", description = "Property container has already left active storage", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    ],
  )
  fun dispose(
    @Parameter(description = "Property container id", example = "0196f1d3-9a1f-7c3a-9b2e-2c1f3a4b5c6d", required = true)
    @PathVariable
    id: UUID,
    @Valid @RequestBody request: DisposeContainerRequest,
  ): PropertyContainerDto = propertyContainerWriteService.dispose(id, request, currentUsername()).publishAfterCommit()

  @PostMapping("/{id}/remove")
  @PreAuthorize("hasRole('ROLE_PRISONER_PROPERTY__RW')")
  @Operation(
    summary = "Remove a property container from active storage",
    description = "Records the container as returned to the prisoner or transferred to another prison, taking it out of active storage and clearing its location. Requires role ROLE_PRISONER_PROPERTY__RW.",
    responses = [
      ApiResponse(responseCode = "200", description = "Property container removed"),
      ApiResponse(responseCode = "400", description = "Invalid request", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "401", description = "Unauthorized - a valid token was not presented", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "403", description = "Forbidden - the ROLE_PRISONER_PROPERTY__RW role is required", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "404", description = "Property container not found", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "409", description = "Property container has already left active storage", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    ],
  )
  fun remove(
    @Parameter(description = "Property container id", example = "0196f1d3-9a1f-7c3a-9b2e-2c1f3a4b5c6d", required = true)
    @PathVariable
    id: UUID,
    @Valid @RequestBody request: RemoveContainerRequest,
  ): PropertyContainerDto = propertyContainerWriteService.remove(id, request, currentUsername()).publishAfterCommit()

  @PostMapping("/combine")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('ROLE_PRISONER_PROPERTY__RW')")
  @Operation(
    summary = "Combine containers into a new sealed container",
    description = "Combines the property of two or more source containers into a single new sealed container. The sources must share one prisoner and prison (inherited by the new container) and are taken out of active storage (COMBINED). Requires role ROLE_PRISONER_PROPERTY__RW.",
    responses = [
      ApiResponse(responseCode = "201", description = "New combined container created"),
      ApiResponse(responseCode = "400", description = "Invalid request (mismatched or removed sources, fewer than two)", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "401", description = "Unauthorized - a valid token was not presented", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "403", description = "Forbidden - the ROLE_PRISONER_PROPERTY__RW role is required", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "404", description = "A source container was not found", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "409", description = "The seal number is already in use by another active container", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    ],
  )
  fun combine(@Valid @RequestBody request: CombineContainersRequest): PropertyContainerDto = propertyContainerWriteService.combine(request, currentUsername()).publishAllAfterCommit()

  @PostMapping("/{id}/move")
  @PreAuthorize("hasRole('ROLE_PRISONER_PROPERTY__RW')")
  @Operation(
    summary = "Move a property container",
    description = "Moves the container to an internal prison location or offsite to the Branston warehouse, recording the move in history. Requires role ROLE_PRISONER_PROPERTY__RW.",
    responses = [
      ApiResponse(responseCode = "200", description = "Property container moved (or already at the target location)"),
      ApiResponse(responseCode = "400", description = "Invalid request", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "401", description = "Unauthorized - a valid token was not presented", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "403", description = "Forbidden - the ROLE_PRISONER_PROPERTY__RW role is required", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "404", description = "Property container not found", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "409", description = "Property container has already left active storage", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    ],
  )
  fun move(
    @Parameter(description = "Property container id", example = "0196f1d3-9a1f-7c3a-9b2e-2c1f3a4b5c6d", required = true)
    @PathVariable
    id: UUID,
    @Valid @RequestBody request: MoveContainerRequest,
  ): PropertyContainerDto = propertyContainerWriteService.move(id, request, currentUsername()).publishAfterCommit()

  private fun currentUsername(): String = authenticationHolder.username ?: authenticationHolder.principal
}
