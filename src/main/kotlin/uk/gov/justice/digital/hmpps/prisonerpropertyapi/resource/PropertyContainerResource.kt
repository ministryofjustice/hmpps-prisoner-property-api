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
import org.springdoc.core.annotations.ParameterObject
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerStatus
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PersonLocation
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.BoxLocationDto
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.BoxLocationSort
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.CombineContainersRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.CreatePropertyContainerRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.DisposeContainerRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.MoveContainerRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.PrisonPropertySummaryDto
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.PrisonerPropertyContainerDto
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.PrisonerPropertyGroupDto
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.PrisonerTimelineItemDto
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.PropertyContainerDto
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.PropertyEventDto
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.RemoveContainerRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.UpdatePropertyContainerRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.event.DomainEventPublisher
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.BoxLocationService
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.CombineResult
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.CreateResult
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
  private val boxLocationService: BoxLocationService,
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

  /** Publishes all the create domain events (the new container, plus any reconciled transfer-in source) after commit. */
  private fun CreateResult.publishAllAfterCommit(): PropertyContainerDto {
    events.forEach(domainEventPublisher::publish)
    return container
  }

  @GetMapping("/prisoner/{prisonerNumber}")
  @Operation(
    summary = "Get the property containers held for a prisoner",
    description = "Requires role ROLE_PRISONER_PROPERTY__RO. Returns an empty list if the prisoner has no property " +
      "containers. Each container is enriched with the prisoner name (prisoner-search), the name of the prison " +
      "holding it (prison-register) and a description of its current location (locations-inside-prison-api), and " +
      "flags whether it is held in the prisoner's current prison. Results can be filtered by status and sorted by " +
      "when each container was last updated.",
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
    @Parameter(description = "Only return containers with one of these statuses. Repeatable; omit for all statuses.", example = "STORED")
    @RequestParam(required = false)
    status: List<ContainerStatus>?,
    @Parameter(description = "Sort direction by last-updated date.", example = "DESC")
    @RequestParam(required = false, defaultValue = "DESC")
    sortDirection: Sort.Direction,
  ): List<PrisonerPropertyContainerDto> = propertyContainerService.getByPrisonerNumber(prisonerNumber, status ?: emptyList(), sortDirection)

  @GetMapping("/prison/{prisonId}")
  @Operation(
    summary = "Get the property containers held in a prison, paged and grouped by prisoner",
    description = "Requires role ROLE_PRISONER_PROPERTY__RO. Returns the establishment-wide property list as a page " +
      "of prisoners (each with all their matching containers), so a prisoner's containers are never split across a " +
      "page boundary - the page total is the number of matching prisoners. Each container is enriched with the " +
      "prisoner name (prisoner-search), prison name (prison-register) and location description " +
      "(locations-inside-prison-api). Optionally filtered by a free-text query (prisoner number, seal number or " +
      "storage location), prisoner number, seal number, container type(s), status and storage location. With no " +
      "status filter, containers that have left active storage (disposed, returned, transferred, combined) are " +
      "hidden; pass a status filter, or includeRemoved=true to also surface returned/disposed containers. Use the " +
      "standard page, size and sort query parameters for pagination.",
    responses = [
      ApiResponse(responseCode = "200", description = "Page of prisoners with their property containers returned"),
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
    @Parameter(description = "Free-text search matched against prisoner number, seal number or storage location", example = "A1234BC")
    @RequestParam(required = false)
    query: String?,
    @Parameter(description = "Filter to a single prisoner number", example = "A1234BC")
    @RequestParam(required = false)
    prisonerNumber: String?,
    @Parameter(description = "Filter to a single seal number", example = "SN8842K1")
    @RequestParam(required = false)
    sealNumber: String?,
    @Parameter(description = "Filter to these container types (repeatable). Omit for all types.", example = "STANDARD")
    @RequestParam(required = false)
    containerType: List<ContainerType>?,
    @Parameter(description = "Filter to these statuses (repeatable). Omit to hide containers that have left active storage.", example = "STORED")
    @RequestParam(required = false)
    status: List<ContainerStatus>?,
    @Parameter(description = "Filter to a storage location code (e.g. PB5638), or BRANSTON for offsite storage", example = "PB5638")
    @RequestParam(required = false)
    storageLocation: String?,
    @Parameter(description = "Also include containers that have been returned or disposed of", example = "false")
    @RequestParam(required = false, defaultValue = "false")
    includeRemoved: Boolean,
    @Parameter(description = "Filter by where the property's owner currently is: IN_ESTABLISHMENT (people held here) or LEFT_ESTABLISHMENT (people no longer here). Resolved from prisoner-search.", example = "IN_ESTABLISHMENT")
    @RequestParam(required = false)
    personLocation: PersonLocation?,
    @ParameterObject
    pageable: Pageable,
  ): Page<PrisonerPropertyGroupDto> = propertyContainerService.getPrisonProperty(
    prisonId = prisonId,
    prisonerNumber = prisonerNumber,
    sealNumber = sealNumber,
    containerTypes = containerType ?: emptyList(),
    statuses = status ?: emptyList(),
    storageLocation = storageLocation,
    includeRemoved = includeRemoved,
    search = query,
    personLocation = personLocation,
    pageable = pageable,
  )

  @GetMapping("/prison/{prisonId}/box-locations")
  @Operation(
    summary = "Get the property box locations in a prison with their current container counts",
    description = "Requires role ROLE_PRISONER_PROPERTY__RO. Returns a page of the BOX locations in the prison " +
      "(from locations-inside-prison-api) annotated with how many property containers are currently held " +
      "there, so a user can pick a suitable place to store property. Empty boxes are included with a count " +
      "of 0. Optionally filtered by a search query matched (case-insensitively) against the box code, local " +
      "name and path hierarchy - the query supports * (any run of characters) and ? (a single character) " +
      "wildcards, and a query with no wildcards is a substring match. Ordered alphabetically by name by " +
      "default, or emptiest-first with sort=FEWEST_CONTAINERS. Use the standard page and size query " +
      "parameters for pagination.",
    responses = [
      ApiResponse(responseCode = "200", description = "Page of box locations returned"),
      ApiResponse(responseCode = "400", description = "Invalid prison id", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "401", description = "Unauthorized - a valid token was not presented", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "403", description = "Forbidden - the ROLE_PRISONER_PROPERTY__RO role is required", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    ],
  )
  fun getBoxLocations(
    @Parameter(description = "Id of the prison", example = "LEI", required = true)
    @Pattern(regexp = "^[A-Z]{2}I|ZZGHI$", message = "Prison id must be 3 characters ending in an I, or ZZGHI")
    @PathVariable
    prisonId: String,
    @Parameter(description = "Ordering of the returned boxes.", example = "NAME")
    @RequestParam(required = false, defaultValue = "NAME")
    sort: BoxLocationSort,
    @Parameter(description = "Filter to boxes whose code, name or path matches this term (supports * and ? wildcards)", example = "recp*")
    @RequestParam(required = false)
    query: String?,
    @ParameterObject
    pageable: Pageable,
  ): Page<BoxLocationDto> = boxLocationService.getBoxLocations(prisonId, sort, query, pageable)

  @GetMapping("/prison/{prisonId}/summary")
  @Operation(
    summary = "Get the whole-prison property summary counts for a prison",
    description = "Requires role ROLE_PRISONER_PROPERTY__RO. Returns the establishment summary tiles for the prison " +
      "as a whole, independent of any list paging or filtering: the number of BOX storage locations configured " +
      "(from locations-inside-prison-api) and how many containers are stored on-site, due to transfer out and due " +
      "to be disposed. 'Due to be returned' is always 0 for now - no status yet represents a pending return.",
    responses = [
      ApiResponse(responseCode = "200", description = "Prison property summary returned"),
      ApiResponse(responseCode = "400", description = "Invalid prison id", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "401", description = "Unauthorized - a valid token was not presented", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "403", description = "Forbidden - the ROLE_PRISONER_PROPERTY__RO role is required", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    ],
  )
  fun getPrisonSummary(
    @Parameter(description = "Id of the prison", example = "LEI", required = true)
    @Pattern(regexp = "^[A-Z]{2}I|ZZGHI$", message = "Prison id must be 3 characters ending in an I, or ZZGHI")
    @PathVariable
    prisonId: String,
  ): PrisonPropertySummaryDto = propertyContainerService.getPrisonPropertySummary(prisonId)

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

  @GetMapping("/{id}/events")
  @Operation(
    summary = "Get a property container's history (its events), newest first",
    description = "Requires role ROLE_PRISONER_PROPERTY__RO. Returns the ordered list of events that make up the " +
      "container's history (created, sealed, moved, transferred, returned, disposed, combined, etc.), newest first. " +
      "Each event carries only the fields relevant to it (e.g. seal number for seal events, from/to location for " +
      "moves, from/to prison for transfers, related container for combines).",
    responses = [
      ApiResponse(responseCode = "200", description = "Property container events returned"),
      ApiResponse(responseCode = "401", description = "Unauthorized - a valid token was not presented", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "403", description = "Forbidden - the ROLE_PRISONER_PROPERTY__RO role is required", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "404", description = "Property container not found", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    ],
  )
  fun getEvents(
    @Parameter(description = "Property container id", example = "0196f1d3-9a1f-7c3a-9b2e-2c1f3a4b5c6d", required = true)
    @PathVariable
    id: UUID,
  ): List<PropertyEventDto> = propertyContainerService.getEvents(id)

  @GetMapping("/prisoner/{prisonerNumber}/events")
  @Operation(
    summary = "Get a prisoner's whole-property history timeline, newest first",
    description = "Requires role ROLE_PRISONER_PROPERTY__RO. Returns a single interleaved timeline of every event " +
      "across all of the prisoner's (non-archived) containers, newest first, plus a de-duplicated \"arrived at ...\" " +
      "item for each prison the prisoner moved into. Prison and location ids are resolved to names, and each " +
      "container event carries the seal number and acting establishment as at that point in its history. Returns an " +
      "empty list if the prisoner has no property.",
    responses = [
      ApiResponse(responseCode = "200", description = "Prisoner property timeline returned"),
      ApiResponse(responseCode = "400", description = "Invalid prisoner number", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "401", description = "Unauthorized - a valid token was not presented", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "403", description = "Forbidden - the ROLE_PRISONER_PROPERTY__RO role is required", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    ],
  )
  fun getPrisonerEvents(
    @Parameter(description = "Prisoner (NOMS) number", example = "A1234BC", required = true)
    @Pattern(regexp = "([a-zA-Z][0-9]{4}[a-zA-Z]{2})", message = "Prisoner number must be in the format A1234BC")
    @PathVariable
    prisonerNumber: String,
  ): List<PrisonerTimelineItemDto> = propertyContainerService.getPrisonerTimeline(prisonerNumber)

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('ROLE_PRISONER_PROPERTY__RW')")
  @Operation(
    summary = "Create a new property container",
    description = "Requires role ROLE_PRISONER_PROPERTY__RW. If `previousSealNumber` matches a container the " +
      "prisoner has due for transfer out at another prison, that container is reconciled as the property " +
      "arriving here on transfer: it is linked to the new record and deactivated (transferred).",
    responses = [
      ApiResponse(responseCode = "201", description = "Property container created"),
      ApiResponse(responseCode = "400", description = "Invalid request", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "401", description = "Unauthorized - a valid token was not presented", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "403", description = "Forbidden - the ROLE_PRISONER_PROPERTY__RW role is required", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "409", description = "The seal number is already in use by another active container", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    ],
  )
  fun create(@Valid @RequestBody request: CreatePropertyContainerRequest): PropertyContainerDto = propertyContainerWriteService.create(request, currentUsername()).publishAllAfterCommit()

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
    description = "Records why the container is leaving the sending prison's active storage: RETURNED to the prisoner, DISPOSED of, or CREATED_IN_ERROR (all terminal, clearing its location and freeing its seal), or TRANSFERRED - which instead reassigns the container to the receiving prison (toPrisonId), keeping it active there. Requires role ROLE_PRISONER_PROPERTY__RW.",
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
