package uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.sync

import com.fasterxml.jackson.annotation.JsonValue
import io.swagger.v3.oas.annotations.media.Schema

/**
 * The legacy NOMIS container type sent on a sync/migrate request. The wire value is the NOMIS
 * description (the underlying reference code is irrelevant and never sent); an unrecognised value is
 * rejected as a bad request.
 */
@Schema(description = "The NOMIS container type description (PPTY_CNTNR reference)")
enum class NomisContainerCode(@get:JsonValue val description: String) {
  BULK("Bulk"),
  VALUABLES("Valuables"),
  CONFISCATED("Confiscated"),
  BRANSTON_STORAGE("Branston Storage"),
  FOR_DESTRUCTION("For Destruction"),
}
