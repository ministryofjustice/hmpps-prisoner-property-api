package uk.gov.justice.digital.hmpps.prisonerpropertyapi.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.check
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.client.PrisonRegisterClient
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ActiveAgency
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ActiveAgencyRepository
import java.time.LocalDateTime
import java.util.Optional

class ActiveAgenciesServiceTest {
  private val repository: ActiveAgencyRepository = mock()
  private val prisonRegisterClient: PrisonRegisterClient = mock()
  private val service = ActiveAgenciesService(repository, prisonRegisterClient)

  @Test
  fun `getActiveAgencies returns the active agency ids sorted`() {
    whenever(repository.findAllByActiveTrue()).thenReturn(
      listOf(
        ActiveAgency("MDI", true, LocalDateTime.now(), "ADMIN"),
        ActiveAgency("LEI", true, LocalDateTime.now(), "ADMIN"),
      ),
    )

    assertThat(service.getActiveAgencies()).containsExactly("LEI", "MDI")
  }

  @Test
  fun `getAllAgencies lists only operational prisons with their active flag, sorted by name`() {
    // CDI is closed/non-operational (not in the active-ids set) so it must be filtered out.
    whenever(prisonRegisterClient.getActivePrisonIds()).thenReturn(setOf("MDI", "LEI", "WWI"))
    whenever(prisonRegisterClient.getPrisonNames()).thenReturn(
      mapOf(
        "MDI" to "Moorland (HMP & YOI)",
        "LEI" to "Leeds (HMP)",
        "WWI" to "Wandsworth (HMP)",
        "CDI" to "Chelmsford (HMP) closed",
      ),
    )
    whenever(repository.findAllByActiveTrue()).thenReturn(
      listOf(ActiveAgency("MDI", true, LocalDateTime.now(), "ADMIN")),
    )

    val result = service.getAllAgencies()

    assertThat(result.map { it.agencyId }).containsExactly("LEI", "MDI", "WWI")
    assertThat(result.map { it.name }).containsExactly("Leeds (HMP)", "Moorland (HMP & YOI)", "Wandsworth (HMP)")
    assertThat(result.single { it.agencyId == "MDI" }.active).isTrue()
    assertThat(result.single { it.agencyId == "LEI" }.active).isFalse()
    assertThat(result.single { it.agencyId == "WWI" }.active).isFalse()
  }

  @Test
  fun `getAllAgencies keeps an already-enabled prison listed even if it is no longer operational`() {
    // ZZI has been switched on but has dropped out of the operational list - it must stay so it can be switched off.
    whenever(prisonRegisterClient.getActivePrisonIds()).thenReturn(setOf("MDI"))
    whenever(prisonRegisterClient.getPrisonNames()).thenReturn(
      mapOf("MDI" to "Moorland (HMP & YOI)", "ZZI" to "Closed prison"),
    )
    whenever(repository.findAllByActiveTrue()).thenReturn(
      listOf(ActiveAgency("ZZI", true, LocalDateTime.now(), "ADMIN")),
    )

    val result = service.getAllAgencies()

    assertThat(result.map { it.agencyId }).containsExactlyInAnyOrder("MDI", "ZZI")
    assertThat(result.single { it.agencyId == "ZZI" }.active).isTrue()
  }

  @Test
  fun `isActive is true only for an active agency`() {
    whenever(repository.findById("MDI")).thenReturn(Optional.of(ActiveAgency("MDI", true, LocalDateTime.now(), "ADMIN")))
    whenever(repository.findById("LEI")).thenReturn(Optional.of(ActiveAgency("LEI", false, LocalDateTime.now(), "ADMIN")))
    whenever(repository.findById("WWI")).thenReturn(Optional.empty())

    assertThat(service.isActive("MDI")).isTrue()
    assertThat(service.isActive("LEI")).isFalse()
    assertThat(service.isActive("WWI")).isFalse()
  }

  @Test
  fun `setActive creates a new row when the agency is not yet configured and returns the resolved status`() {
    whenever(repository.findById("MDI")).thenReturn(Optional.empty())
    whenever(repository.save(any())).thenAnswer { it.arguments[0] }
    whenever(prisonRegisterClient.getPrisonNames()).thenReturn(mapOf("MDI" to "Moorland (HMP & YOI)"))

    val result = service.setActive("MDI", true, "ADMIN_USER")

    assertThat(result.agencyId).isEqualTo("MDI")
    assertThat(result.name).isEqualTo("Moorland (HMP & YOI)")
    assertThat(result.active).isTrue()
    verify(repository).save(
      check {
        assertThat(it.agencyId).isEqualTo("MDI")
        assertThat(it.active).isTrue()
        assertThat(it.updatedBy).isEqualTo("ADMIN_USER")
      },
    )
  }

  @Test
  fun `setActive falls back to the agency id when prison-register has no name`() {
    whenever(repository.findById("ZZI")).thenReturn(Optional.empty())
    whenever(repository.save(any())).thenAnswer { it.arguments[0] }
    whenever(prisonRegisterClient.getPrisonNames()).thenReturn(emptyMap())

    assertThat(service.setActive("ZZI", true, "ADMIN_USER").name).isEqualTo("ZZI")
  }

  @Test
  fun `setActive updates the existing row when the agency is already configured`() {
    val existing = ActiveAgency("MDI", true, LocalDateTime.now().minusDays(1), "OLD_USER")
    whenever(repository.findById("MDI")).thenReturn(Optional.of(existing))
    whenever(repository.save(any())).thenAnswer { it.arguments[0] }

    service.setActive("MDI", false, "ADMIN_USER")

    verify(repository).save(
      check {
        assertThat(it).isSameAs(existing)
        assertThat(it.active).isFalse()
        assertThat(it.updatedBy).isEqualTo("ADMIN_USER")
      },
    )
  }
}
