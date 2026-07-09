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
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.AgencyStatusDto
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.SetActiveAgencyRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.ActiveAgenciesService
import uk.gov.justice.hmpps.kotlin.auth.HmppsAuthenticationHolder
import uk.gov.justice.hmpps.kotlin.common.ErrorResponse

@RestController
@Validated
@RequestMapping("/active-agencies", produces = [APPLICATION_JSON_VALUE])
@PreAuthorize("hasRole('ROLE_PRISONER_PROPERTY__ADMIN')")
@Tag(name = "Active agencies")
@SecurityRequirement(name = "bearer-jwt")
class ActiveAgenciesResource(
  private val activeAgenciesService: ActiveAgenciesService,
  private val authenticationHolder: HmppsAuthenticationHolder,
) {

  private fun currentUsername(): String = authenticationHolder.username ?: authenticationHolder.principal

  @GetMapping
  @Operation(
    summary = "List the agencies the property service is switched on for",
    description = "Returns the ids of the active agencies (prisons). The same list is published under /info as " +
      "\"activeAgencies\". Requires role ROLE_PRISONER_PROPERTY__ADMIN.",
    responses = [
      ApiResponse(responseCode = "200", description = "Active agency ids returned"),
      ApiResponse(responseCode = "401", description = "Unauthorized - a valid token was not presented", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "403", description = "Forbidden - the ROLE_PRISONER_PROPERTY__ADMIN role is required", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    ],
  )
  fun getActiveAgencies(): List<String> = activeAgenciesService.getActiveAgencies()

  @GetMapping("/all")
  @Operation(
    summary = "List all prisons with whether the property service is switched on for each",
    description = "Returns every prison (from prison-register) with an `active` flag, for the rollout admin console. " +
      "Requires role ROLE_PRISONER_PROPERTY__ADMIN.",
    responses = [
      ApiResponse(responseCode = "200", description = "Prisons with their active state returned"),
      ApiResponse(responseCode = "401", description = "Unauthorized - a valid token was not presented", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "403", description = "Forbidden - the ROLE_PRISONER_PROPERTY__ADMIN role is required", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    ],
  )
  fun getAllAgencies(): List<AgencyStatusDto> = activeAgenciesService.getAllAgencies()

  @PutMapping("/{agencyId}")
  @Operation(
    summary = "Switch the property service on or off for an agency",
    description = "Activates or deactivates the property service for the given agency (prison). Idempotent. " +
      "Requires role ROLE_PRISONER_PROPERTY__ADMIN.",
    responses = [
      ApiResponse(responseCode = "200", description = "Agency state updated"),
      ApiResponse(responseCode = "400", description = "Invalid request", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "401", description = "Unauthorized - a valid token was not presented", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
      ApiResponse(responseCode = "403", description = "Forbidden - the ROLE_PRISONER_PROPERTY__ADMIN role is required", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    ],
  )
  fun setActiveAgency(
    @Parameter(description = "Agency (prison) id", example = "MDI", required = true)
    @PathVariable
    agencyId: String,
    @Valid @RequestBody request: SetActiveAgencyRequest,
  ): AgencyStatusDto = activeAgenciesService.setActive(agencyId, request.active, currentUsername())
}
