package uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.sar

import io.swagger.v3.oas.annotations.media.Schema
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerStatus
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainer
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.RemovalOutcome
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * One sealed container of a prisoner's property, and everything that happened to it, as it appears in a
 * subject access request response.
 *
 * What is left out is as deliberate as what is included, and follows the SAR integration guiding principles:
 *  - the container's own id, and the id of any related container, are internal identifiers and are excluded;
 *    [SarPropertyEvent.relatedContainerSealNumber] carries the part a person can actually recognise;
 *  - the denormalised `current*` mirror columns on the entity are excluded - they are derived state kept for
 *    query performance, not something held about the prisoner, and repeating them would duplicate data;
 *  - nothing owned by another service is fetched or included: no prisoner name, no movement history, no
 *    location or prison names. Codes travel raw and the template resolves them;
 *  - no NOMIS booking id, and no sync-only field such as the NOMIS property container id.
 *
 * [prisonerNumber] is the one identifier that is included, which the guiding principles single out as an
 * exception so a report can be tied back to its subject. It is not rendered in the template body - the SAR
 * tool already prints it in the header of every page.
 */
@Schema(description = "A sealed container of a prisoner's property and its full history")
data class SarPropertyContainer(
  @Schema(description = "Prison number of the prisoner whose property this is", example = "A1234AA")
  val prisonerNumber: String,

  @Schema(description = "The kind of container", example = "STANDARD")
  val containerType: ContainerType,

  @Schema(description = "Prison holding the container", example = "LEI")
  val prisonId: String,

  @Schema(description = "The container's current status", example = "STORED")
  val status: ContainerStatus,

  @Schema(description = "The container's current seal number", example = "SEAL12345", nullable = true)
  val sealNumber: String?,

  @Schema(description = "When the container was created")
  val createdDateTime: LocalDateTime,

  @Schema(description = "Username of the member of staff who created the container", example = "AUSER_GEN")
  val createdByUsername: String,

  @Schema(description = "Date the container is proposed for disposal, if one has been set", example = "2026-09-15", nullable = true)
  val proposedDisposalDate: LocalDate?,

  @Schema(description = "Why the container left active storage, if it has", example = "RETURNED", nullable = true)
  val removalOutcome: RemovalOutcome?,

  @Schema(description = "Date the container left active storage, if it has", example = "2026-09-20", nullable = true)
  val removalDate: LocalDate?,

  @Schema(description = "Everything that happened to the container, oldest first")
  val events: List<SarPropertyEvent>,
) {
  companion object {
    /**
     * Events are ordered oldest first so each container's history reads forwards as a story, even though the
     * containers themselves are returned newest first as the integration guide requires.
     */
    fun from(container: PropertyContainer) = SarPropertyContainer(
      prisonerNumber = container.prisonerNumber,
      containerType = container.containerType,
      prisonId = container.prisonId,
      status = container.currentStatus(),
      sealNumber = container.currentSealNumber,
      createdDateTime = container.createDateTime,
      createdByUsername = container.createdByUserId,
      proposedDisposalDate = container.proposedDisposalDate,
      removalOutcome = container.removalOutcome,
      removalDate = container.removalDate,
      events = container.events.sortedBy { it.eventDateTime }.map { SarPropertyEvent.from(it) },
    )
  }
}
