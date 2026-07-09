package uk.gov.justice.digital.hmpps.prisonerpropertyapi.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.check
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ActiveAgency
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ActiveAgencyRepository
import java.time.LocalDateTime
import java.util.Optional

class ActiveAgenciesServiceTest {
  private val repository: ActiveAgencyRepository = mock()
  private val service = ActiveAgenciesService(repository)

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
  fun `isActive is true only for an active agency`() {
    whenever(repository.findById("MDI")).thenReturn(Optional.of(ActiveAgency("MDI", true, LocalDateTime.now(), "ADMIN")))
    whenever(repository.findById("LEI")).thenReturn(Optional.of(ActiveAgency("LEI", false, LocalDateTime.now(), "ADMIN")))
    whenever(repository.findById("WWI")).thenReturn(Optional.empty())

    assertThat(service.isActive("MDI")).isTrue()
    assertThat(service.isActive("LEI")).isFalse()
    assertThat(service.isActive("WWI")).isFalse()
  }

  @Test
  fun `setActive creates a new row when the agency is not yet configured`() {
    whenever(repository.findById("MDI")).thenReturn(Optional.empty())
    whenever(repository.save(any())).thenAnswer { it.arguments[0] }

    service.setActive("MDI", true, "ADMIN_USER")

    verify(repository).save(
      check {
        assertThat(it.agencyId).isEqualTo("MDI")
        assertThat(it.active).isTrue()
        assertThat(it.updatedBy).isEqualTo("ADMIN_USER")
      },
    )
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
