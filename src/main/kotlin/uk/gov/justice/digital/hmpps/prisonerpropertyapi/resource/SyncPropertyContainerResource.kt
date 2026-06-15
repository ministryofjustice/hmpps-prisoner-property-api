package uk.gov.justice.digital.hmpps.prisonerpropertyapi.resource

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.MediaType.APPLICATION_JSON_VALUE
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.PropertyContainerDto
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.sync.SyncPropertyContainerRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.sync.SyncPropertyContainerResponse
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.event.DomainEventPublisher
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.PropertyContainerService
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.SyncPropertyContainerService
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.SyncResult
import uk.gov.justice.hmpps.kotlin.common.ErrorResponse
import java.util.UUID

@RestController
@Validated
@RequestMapping("/sync/property-containers", produces = [APPLICATION_JSON_VALUE])
@PreAuthorize("hasRole('ROLE_PRISONER_PROPERTY__SYNC')")
@Tag(name = "Sync with NOMIS")
@SecurityRequirement(name = "bearer-jwt")
class SyncPropertyContainerResource(
  private val syncPropertyContainerService: SyncPropertyContainerService,
  private val propertyContainerService: PropertyContainerService,
  private val domainEventPublisher: DomainEventPublisher,
) {

  /** Publishes the domain event (if any) after the service transaction has committed. */
  private fun SyncResult.publishAfterCommit(): SyncPropertyContainerResponse {
    event?.let(domainEventPublisher::publish)
    return response
  }

  @PostMapping("/upsert")
  @Operation(
    summary = "Create or update a property container from a NOMIS change",
    description = "Used by the NOMIS sync to keep DPS in step with ongoing NOMIS changes. Supply the DPS id to " +
      "update an existing container, or omit it to create a new one. Requires role ROLE_PRISONER_PROPERTY__SYNC. " +
      "Re-sending an unchanged snapshot makes no change.",
    responses = [
      ApiResponse(responseCode = "200", description = "Container created or updated"),
      ApiResponse(responseCode = "400", description = "Invalid request", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "401", description = "Unauthorized - a valid token was not presented", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "403", description = "Forbidden - the ROLE_PRISONER_PROPERTY__SYNC role is required", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "404", description = "The supplied DPS id does not exist", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    ],
  )
  fun upsert(@Valid @RequestBody request: SyncPropertyContainerRequest): SyncPropertyContainerResponse = syncPropertyContainerService.sync(request).publishAfterCommit()

  @PostMapping("/migrate")
  @Operation(
    summary = "Migrate a property container from NOMIS",
    description = "Used by the initial bulk migration from NOMIS. Behaves like upsert but raises no domain event, " +
      "as migrated data is a one-off load that downstream systems do not need to be notified of. " +
      "Requires role ROLE_PRISONER_PROPERTY__SYNC.",
    responses = [
      ApiResponse(responseCode = "200", description = "Container migrated"),
      ApiResponse(responseCode = "400", description = "Invalid request", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "401", description = "Unauthorized - a valid token was not presented", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "403", description = "Forbidden - the ROLE_PRISONER_PROPERTY__SYNC role is required", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "404", description = "The supplied DPS id does not exist", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    ],
  )
  fun migrate(@Valid @RequestBody request: SyncPropertyContainerRequest): SyncPropertyContainerResponse = syncPropertyContainerService.migrate(request).publishAfterCommit()

  @GetMapping("/{id}")
  @Operation(
    summary = "Get a synced property container by its DPS id, for reconciliation",
    description = "Requires role ROLE_PRISONER_PROPERTY__SYNC.",
    responses = [
      ApiResponse(responseCode = "200", description = "Property container returned"),
      ApiResponse(responseCode = "401", description = "Unauthorized - a valid token was not presented", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "403", description = "Forbidden - the ROLE_PRISONER_PROPERTY__SYNC role is required", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "404", description = "Property container not found", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    ],
  )
  fun getSyncedContainerById(
    @Parameter(description = "Property container id", example = "0196f1d3-9a1f-7c3a-9b2e-2c1f3a4b5c6d", required = true)
    @PathVariable
    id: UUID,
  ): PropertyContainerDto = propertyContainerService.getById(id)
}
