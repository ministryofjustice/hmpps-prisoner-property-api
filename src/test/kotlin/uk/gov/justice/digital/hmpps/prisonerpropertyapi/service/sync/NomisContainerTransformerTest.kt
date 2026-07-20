package uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.sync

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.StorageLocationType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.sync.NomisContainerCode
import java.time.LocalDateTime
import java.util.UUID

class NomisContainerTransformerTest {

  private val transformer = NomisContainerTransformer()

  @ParameterizedTest
  @CsvSource(
    "BULK,STANDARD",
    "VALUABLES,VALUABLES",
    "CONFISCATED,CONFISCATED",
    "BRANSTON_STORAGE,EXCESS",
    "FOR_DESTRUCTION,STANDARD",
  )
  fun `maps container codes to types`(code: NomisContainerCode, expected: ContainerType) {
    assertThat(transformer.mapType(code)).isEqualTo(expected)
  }

  @Test
  fun `keeps a real seal mark`() {
    assertThat(transformer.resolveSeal(123, "SN8842K1")).isEqualTo("SN8842K1")
  }

  @Test
  fun `generates a flagged placeholder for a missing seal`() {
    assertThat(transformer.resolveSeal(123, null)).isEqualTo("MISSING-123")
    assertThat(transformer.resolveSeal(456, "  ")).isEqualTo("MISSING-456")
  }

  @Test
  fun `resolves an internal location for a normal container`() {
    val location = transformer.resolveLocation(request(NomisContainerCode.BULK, internalLocationId = INTERNAL_LOCATION))

    assertThat(location?.type).isEqualTo(StorageLocationType.INTERNAL)
    assertThat(location?.internalLocationId).isEqualTo(INTERNAL_LOCATION)
  }

  @Test
  fun `resolves Branston with no internal location for a Branston Storage container`() {
    val location = transformer.resolveLocation(request(NomisContainerCode.BRANSTON_STORAGE, internalLocationId = null))

    assertThat(location?.type).isEqualTo(StorageLocationType.BRANSTON)
    assertThat(location?.internalLocationId).isNull()
  }

  @Test
  fun `resolves an internal location for a Branston Storage container held at a prison location`() {
    // Excess property may be held at a prison location as well as offsite: an internal location wins over Branston.
    val location = transformer.resolveLocation(request(NomisContainerCode.BRANSTON_STORAGE, internalLocationId = INTERNAL_LOCATION))

    assertThat(location?.type).isEqualTo(StorageLocationType.INTERNAL)
    assertThat(location?.internalLocationId).isEqualTo(INTERNAL_LOCATION)
  }

  @Test
  fun `resolves no location when a non-Branston container has no internal location`() {
    assertThat(transformer.resolveLocation(request(NomisContainerCode.BULK, internalLocationId = null))).isNull()
  }

  private fun request(containerCode: NomisContainerCode, internalLocationId: UUID? = INTERNAL_LOCATION) = uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.sync.SyncPropertyContainerRequest(
    nomisPropertyContainerId = 123,
    prisonerNumber = "A1234BC",
    prisonId = "LEI",
    containerCode = containerCode,
    internalLocationId = internalLocationId,
    createDateTime = LocalDateTime.parse("2026-01-01T09:00:00"),
    createUsername = "USER1",
  )

  private companion object {
    private val INTERNAL_LOCATION: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
  }
}
