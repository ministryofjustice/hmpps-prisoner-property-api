package uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.sync

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.StorageLocationType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.sync.NomisContainerCode
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.sync.SyncPropertyContainerRequest
import java.util.UUID

/**
 * Translates the legacy NOMIS fields of a [SyncPropertyContainerRequest] into the DPS domain. The
 * mapping rules (container code, seal placeholder, Branston location) live here so this API is the
 * single source of truth for the transform.
 */
@Component
class NomisContainerTransformer {

  /**
   * Maps a NOMIS container code to a DPS [ContainerType].
   *
   * Note the two re-categorised legacy values: 'Branston Storage' becomes a location (type EXCESS)
   * and 'For Destruction' becomes a status rather than a type, so it has no real type of its own and
   * defaults to STANDARD - see [effectiveLocation] and the disposal handling in the service.
   */
  fun mapType(containerCode: NomisContainerCode): ContainerType = when (containerCode) {
    NomisContainerCode.BULK -> ContainerType.STANDARD
    NomisContainerCode.VALUABLES -> ContainerType.VALUABLES
    NomisContainerCode.CONFISCATED -> ContainerType.CONFISCATED
    NomisContainerCode.BRANSTON_STORAGE -> ContainerType.EXCESS
    NomisContainerCode.FOR_DESTRUCTION -> {
      // FLAG: 'For Destruction' is a status in DPS, not a type. We default the type to STANDARD;
      // the disposal status surfaces via the proposed/disposed dates. Staff may need to amend.
      log.warn("Mapping legacy 'For Destruction' container to type STANDARD - disposal status is derived from its dates")
      ContainerType.STANDARD
    }
  }

  /**
   * Resolves the seal number. NOMIS SEAL_MARK is unreliable: when it is missing or blank a flagged
   * placeholder is generated to prompt staff to amend it. Uniqueness is not enforced on this path.
   */
  fun resolveSeal(nomisPropertyContainerId: Long, sealMark: String?): String = sealMark?.takeIf { it.isNotBlank() } ?: "$MISSING_SEAL_PREFIX$nomisPropertyContainerId"

  /**
   * Resolves the storage location. An internal location id, when present, always wins: the container is
   * held at that internal prison location - including a 'Branston Storage' (EXCESS) container, which may be
   * held offsite *or* at a prison location (the type and the location are independent). A 'Branston Storage'
   * container with no internal location id is held offsite at the Branston warehouse. A container with
   * neither has no recorded location (null).
   */
  fun resolveLocation(request: SyncPropertyContainerRequest): ResolvedLocation? = when {
    request.internalLocationId != null -> ResolvedLocation(StorageLocationType.INTERNAL, request.internalLocationId)
    request.containerCode == NomisContainerCode.BRANSTON_STORAGE -> ResolvedLocation(StorageLocationType.BRANSTON, null)
    else -> null
  }

  companion object {
    const val MISSING_SEAL_PREFIX = "MISSING-"

    private val log = LoggerFactory.getLogger(this::class.java)
  }
}

/** A resolved storage location: its [type] and, for an internal prison location, its [internalLocationId]. */
data class ResolvedLocation(val type: StorageLocationType, val internalLocationId: UUID?)
