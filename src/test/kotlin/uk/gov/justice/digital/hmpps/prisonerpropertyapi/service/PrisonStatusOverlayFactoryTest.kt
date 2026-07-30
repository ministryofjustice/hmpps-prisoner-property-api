package uk.gov.justice.digital.hmpps.prisonerpropertyapi.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.client.Prisoner
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.client.PrisonerSearchClient
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerStatus
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.OwnerLocation
import java.time.LocalDate

class PrisonStatusOverlayFactoryTest {

  private val prisonerSearchClient = mock<PrisonerSearchClient>()
  private val factory = PrisonStatusOverlayFactory(prisonerSearchClient, ContainerStatusResolver())

  @Test
  fun `groups the prison's property owners by the status their property reads`() {
    whenever(prisonerSearchClient.getPrisoners(any())).thenReturn(
      mapOf(
        "A0001AA" to prisoner("A0001AA", "LEI"),
        "B0002BB" to prisoner("B0002BB", "MDI"),
        "C0003CC" to prisoner("C0003CC", "OUT", movement = "REL"),
        "D0004DD" to prisoner("D0004DD", "LEI", releaseDate = LocalDate.now()),
      ),
    )

    val overlay = factory.overlayFor("LEI", listOf("A0001AA", "B0002BB", "C0003CC", "D0004DD")).overlay!!

    assertThat(overlay.effectiveStatusOf("A0001AA", ContainerStatus.STORED)).isEqualTo(ContainerStatus.STORED)
    assertThat(overlay.effectiveStatusOf("B0002BB", ContainerStatus.STORED)).isEqualTo(ContainerStatus.DUE_FOR_TRANSFER_OUT)
    assertThat(overlay.effectiveStatusOf("C0003CC", ContainerStatus.STORED)).isEqualTo(ContainerStatus.DUE_FOR_RETURN)
    assertThat(overlay.effectiveStatusOf("D0004DD", ContainerStatus.STORED)).isEqualTo(ContainerStatus.DUE_FOR_RETURN)
    assertThat(overlay.unresolved).isEmpty()
  }

  @Test
  fun `describes which persisted statuses read as each shown status, per group of owners`() {
    whenever(prisonerSearchClient.getPrisoners(any())).thenReturn(
      mapOf("A0001AA" to prisoner("A0001AA", "LEI"), "B0002BB" to prisoner("B0002BB", "MDI")),
    )

    val overlay = factory.overlayFor("LEI", listOf("A0001AA", "B0002BB")).overlay!!

    // The owner who is here: stored and stale transfer-out property both read as stored, but a recorded
    // due-for-return keeps its own status - so it is not in this bucket.
    assertThat(overlay.matchesFor(ContainerStatus.STORED)).containsExactly(
      setOf("A0001AA") to setOf(ContainerStatus.STORED, ContainerStatus.DUE_FOR_TRANSFER_OUT),
    )
    // The owner elsewhere: whatever their property's own status, it needs to follow them.
    assertThat(overlay.matchesFor(ContainerStatus.DUE_FOR_TRANSFER_OUT)).containsExactly(
      setOf("B0002BB") to OwnerLocation.LIVE_STATUSES,
    )
    assertThat(overlay.matchesFor(ContainerStatus.DUE_FOR_RETURN)).containsExactly(
      setOf("A0001AA") to setOf(ContainerStatus.DUE_FOR_RETURN),
    )
  }

  @Test
  fun `prisoners the lookup could not resolve keep their property's persisted status`() {
    whenever(prisonerSearchClient.getPrisoners(any())).thenReturn(mapOf("A0001AA" to prisoner("A0001AA", "LEI")))

    val overlay = factory.overlayFor("LEI", listOf("A0001AA", "B0002BB")).overlay!!

    assertThat(overlay.unresolved).containsExactly("B0002BB")
    assertThat(overlay.effectiveStatusOf("B0002BB", ContainerStatus.DUE_FOR_TRANSFER_OUT)).isEqualTo(ContainerStatus.DUE_FOR_TRANSFER_OUT)
    assertThat(overlay.effectiveStatusOf("A0001AA", ContainerStatus.DUE_FOR_TRANSFER_OUT)).isEqualTo(ContainerStatus.STORED)
  }

  @Test
  fun `a failed lookup leaves every owner unresolved rather than failing the read`() {
    // prisoner-search degrades a failed chunk to no prisoners, so the whole prison falls back together.
    whenever(prisonerSearchClient.getPrisoners(any())).thenReturn(emptyMap())

    val overlay = factory.overlayFor("LEI", listOf("A0001AA", "B0002BB")).overlay!!

    assertThat(overlay.unresolved).containsExactlyInAnyOrder("A0001AA", "B0002BB")
    // Unclassified property keeps its own status, matching the behaviour before any of this existed.
    assertThat(overlay.matchesFor(ContainerStatus.STORED))
      .containsExactly(setOf("A0001AA", "B0002BB") to setOf(ContainerStatus.STORED))
  }

  @Test
  fun `does not call prisoner-search when the prison holds no live property`() {
    assertThat(factory.overlayFor("LEI", emptyList())).isEqualTo(OwnerClassification.NONE)
    verify(prisonerSearchClient, never()).getPrisoners(any())
  }

  @Test
  fun `returns the resolved prisoners so callers need not look them up again`() {
    whenever(prisonerSearchClient.getPrisoners(any())).thenReturn(mapOf("A0001AA" to prisoner("A0001AA", "LEI")))

    assertThat(factory.overlayFor("LEI", listOf("A0001AA")).prisoners).containsOnlyKeys("A0001AA")
  }

  @Test
  fun `de-duplicates the candidates before looking them up`() {
    whenever(prisonerSearchClient.getPrisoners(any())).thenReturn(mapOf("A0001AA" to prisoner("A0001AA", "LEI")))

    factory.overlayFor("LEI", listOf("A0001AA", "A0001AA"))

    verify(prisonerSearchClient).getPrisoners(setOf("A0001AA"))
  }

  private fun prisoner(
    prisonerNumber: String,
    prisonId: String,
    movement: String = "ADM",
    releaseDate: LocalDate? = null,
  ) = Prisoner(
    prisonerNumber = prisonerNumber,
    firstName = "John",
    lastName = "Smith",
    prisonId = prisonId,
    prisonName = null,
    cellLocation = null,
    lastMovementTypeCode = movement,
    confirmedReleaseDate = releaseDate,
  )
}
