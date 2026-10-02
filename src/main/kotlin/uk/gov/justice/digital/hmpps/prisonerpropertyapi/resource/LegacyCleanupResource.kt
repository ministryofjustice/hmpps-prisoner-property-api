package uk.gov.justice.digital.hmpps.prisonerpropertyapi.resource

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType.APPLICATION_JSON_VALUE
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.cleanup.LegacyCleanupJobDto
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.cleanup.LegacyCleanupPreviewDto
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.cleanup.LegacyCleanupService
import uk.gov.justice.hmpps.kotlin.auth.HmppsAuthenticationHolder
import uk.gov.justice.hmpps.kotlin.common.ErrorResponse
import java.util.UUID

/**
 * The legacy property clean-up, run from the rollout admin console: preview what a run would close at a
 * prison under the fixed 13-month retention rule, start one, and follow its progress. Sits under `/active-agencies` because it is part of switching a
 * prison on, and carries the same admin role. See `docs/legacy-cleanup.md`.
 */
@RestController
@RequestMapping("/active-agencies", produces = [APPLICATION_JSON_VALUE])
@PreAuthorize("hasRole('ROLE_PRISONER_PROPERTY__ADMIN')")
@Tag(name = "Active agencies")
@SecurityRequirement(name = "bearer-jwt")
class LegacyCleanupResource(
  private val legacyCleanupService: LegacyCleanupService,
  private val authenticationHolder: HmppsAuthenticationHolder,
) {

  private fun currentUsername(): String = authenticationHolder.username ?: authenticationHolder.principal

  @GetMapping("/{agencyId}/cleanup/preview")
  @Operation(
    summary = "Preview what a legacy clean-up would close at a prison",
    description = "Applies the 13-month retention rule to the live containers held at the prison: counts those whose " +
      "owner was released, died, escaped or absconded, or moved to another prison more than 13 months ago - the ones a " +
      "run would mark removed - alongside the totals currently shown as due for return / due for transfer out, what is " +
      "left alone and why (confiscated property and property with a disposal date still to come are never closed), and " +
      "how old the backlog is. Changes nothing. Requires role ROLE_PRISONER_PROPERTY__ADMIN.",
    responses = [
      ApiResponse(responseCode = "200", description = "Preview returned"),
      ApiResponse(responseCode = "400", description = "Too many containers at the prison to classify", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "401", description = "Unauthorized - a valid token was not presented", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "403", description = "Forbidden - the ROLE_PRISONER_PROPERTY__ADMIN role is required", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    ],
  )
  fun previewLegacyCleanup(
    @Parameter(description = "Agency (prison) id", example = "MDI", required = true)
    @PathVariable
    agencyId: String,
  ): LegacyCleanupPreviewDto = legacyCleanupService.preview(agencyId)

  @PostMapping("/{agencyId}/cleanup")
  @ResponseStatus(HttpStatus.ACCEPTED)
  @Operation(
    summary = "Run a legacy clean-up at a prison",
    description = "Snapshots the containers the preview would close as a job and queues it for processing, returning " +
      "immediately. Each container is then marked removed in its own transaction, by the system user LEGACY_CLEANUP and " +
      "stamped with the job id, and a `prison-property.container.updated` event published for it, so NOMIS is brought " +
      "into line. Takes no request body. Follow progress with the job endpoints. One job may be in flight per prison. " +
      "Requires role ROLE_PRISONER_PROPERTY__ADMIN.",
    responses = [
      ApiResponse(responseCode = "202", description = "Clean-up queued; the job is returned"),
      ApiResponse(responseCode = "400", description = "Too many containers at the prison to classify", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "401", description = "Unauthorized - a valid token was not presented", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "403", description = "Forbidden - the ROLE_PRISONER_PROPERTY__ADMIN role is required", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "409", description = "A clean-up is already pending or running for this prison", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    ],
  )
  fun startLegacyCleanup(
    @Parameter(description = "Agency (prison) id", example = "MDI", required = true)
    @PathVariable
    agencyId: String,
  ): LegacyCleanupJobDto = legacyCleanupService.start(agencyId, currentUsername())

  @GetMapping("/{agencyId}/cleanup")
  @Operation(
    summary = "List the legacy clean-up jobs run at a prison, newest first",
    description = "Every job requested for the prison with its status and counts, without items. " +
      "Requires role ROLE_PRISONER_PROPERTY__ADMIN.",
    responses = [
      ApiResponse(responseCode = "200", description = "Jobs returned"),
      ApiResponse(responseCode = "401", description = "Unauthorized - a valid token was not presented", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "403", description = "Forbidden - the ROLE_PRISONER_PROPERTY__ADMIN role is required", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    ],
  )
  fun listLegacyCleanupJobs(
    @Parameter(description = "Agency (prison) id", example = "MDI", required = true)
    @PathVariable
    agencyId: String,
  ): List<LegacyCleanupJobDto> = legacyCleanupService.getJobs(agencyId)

  @GetMapping("/cleanup/{jobId}")
  @Operation(
    summary = "Get a legacy clean-up job with its items",
    description = "The job's status and counts plus every container it set out to close and what happened to each. " +
      "Requires role ROLE_PRISONER_PROPERTY__ADMIN.",
    responses = [
      ApiResponse(responseCode = "200", description = "Job returned"),
      ApiResponse(responseCode = "401", description = "Unauthorized - a valid token was not presented", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "403", description = "Forbidden - the ROLE_PRISONER_PROPERTY__ADMIN role is required", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "404", description = "No such job", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    ],
  )
  fun getLegacyCleanupJob(
    @Parameter(description = "Job id", required = true)
    @PathVariable
    jobId: UUID,
  ): LegacyCleanupJobDto = legacyCleanupService.getJob(jobId)
}
