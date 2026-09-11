package uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.cleanup

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.client.Prisoner
import java.time.LocalDate

class LegacyCleanupRuleTest {

  private val rule = LegacyCleanupRule()
  private val cutoff = LocalDate.parse("2026-08-14")

  private fun prisoner(
    prisonId: String?,
    lastMovementTypeCode: String? = "ADM",
    lastMovementDate: LocalDate? = null,
    previousPrisonId: String? = null,
    previousPrisonLeavingDate: LocalDate? = null,
    lastAdmissionDate: LocalDate? = null,
    confirmedReleaseDate: LocalDate? = null,
  ) = Prisoner(
    prisonerNumber = "A1234BC",
    firstName = "JOHN",
    lastName = "SMITH",
    prisonId = prisonId,
    prisonName = null,
    cellLocation = null,
    lastMovementTypeCode = lastMovementTypeCode,
    confirmedReleaseDate = confirmedReleaseDate,
    lastMovementDate = lastMovementDate,
    previousPrisonId = previousPrisonId,
    previousPrisonLeavingDate = previousPrisonLeavingDate,
    lastAdmissionDate = lastAdmissionDate,
  )

  @Test
  fun `an unresolved owner is never touched`() {
    assertThat(rule.decide(null, "LEI", cutoff)).isEqualTo(CleanupDecision.NotEligible(IneligibleReason.UNRESOLVED))
    assertThat(rule.decide(prisoner(prisonId = null), "LEI", cutoff)).isEqualTo(CleanupDecision.NotEligible(IneligibleReason.UNRESOLVED))
  }

  @Test
  fun `an owner still at the holding prison is never touched, even with a confirmed release date tomorrow`() {
    val here = prisoner(prisonId = "LEI", confirmedReleaseDate = LocalDate.parse("2026-01-01"))
    assertThat(rule.decide(here, "LEI", cutoff)).isEqualTo(CleanupDecision.NotEligible(IneligibleReason.OWNER_HERE))
  }

  @Test
  fun `an owner in transit is never touched`() {
    val inTransit = prisoner(prisonId = "TRN", lastMovementTypeCode = "TRN", lastMovementDate = LocalDate.parse("2020-01-01"))
    assertThat(rule.decide(inTransit, "LEI", cutoff)).isEqualTo(CleanupDecision.NotEligible(IneligibleReason.IN_TRANSIT))
  }

  @Nested
  inner class Released {
    @Test
    fun `released on or before the cut-off is returned, dated to the release`() {
      val released = prisoner(prisonId = "OUT", lastMovementTypeCode = "REL", lastMovementDate = cutoff)
      assertThat(rule.decide(released, "LEI", cutoff)).isEqualTo(CleanupDecision.Return(eventDate = cutoff))
    }

    @Test
    fun `released after the cut-off is too recent`() {
      val released = prisoner(prisonId = "OUT", lastMovementTypeCode = "REL", lastMovementDate = cutoff.plusDays(1))
      assertThat(rule.decide(released, "LEI", cutoff)).isEqualTo(CleanupDecision.NotEligible(IneligibleReason.TOO_RECENT))
    }

    @Test
    fun `out but not by a release movement is left alone`() {
      val atCourt = prisoner(prisonId = "OUT", lastMovementTypeCode = "CRT", lastMovementDate = LocalDate.parse("2020-01-01"))
      assertThat(rule.decide(atCourt, "LEI", cutoff)).isEqualTo(CleanupDecision.NotEligible(IneligibleReason.NOT_RELEASED_MOVEMENT))
    }

    @Test
    fun `released with no movement date is never guessed at`() {
      val released = prisoner(prisonId = "OUT", lastMovementTypeCode = "REL", lastMovementDate = null)
      assertThat(rule.decide(released, "LEI", cutoff)).isEqualTo(CleanupDecision.NotEligible(IneligibleReason.NO_MOVEMENT_DATE))
    }
  }

  @Nested
  inner class Transferred {
    @Test
    fun `came straight from here - the leaving date is exact`() {
      val moved = prisoner(
        prisonId = "MDI",
        previousPrisonId = "LEI",
        previousPrisonLeavingDate = LocalDate.parse("2026-06-01"),
        lastAdmissionDate = LocalDate.parse("2026-06-02"),
      )
      assertThat(rule.decide(moved, "LEI", cutoff)).isEqualTo(CleanupDecision.Transfer(toPrisonId = "MDI", dateLeft = LocalDate.parse("2026-06-01")))
    }

    @Test
    fun `went somewhere else in between - the admission date to where they are now is the bound`() {
      val moved = prisoner(
        prisonId = "MDI",
        previousPrisonId = "BXI",
        previousPrisonLeavingDate = LocalDate.parse("2026-07-01"),
        lastAdmissionDate = LocalDate.parse("2026-07-02"),
      )
      assertThat(rule.decide(moved, "LEI", cutoff)).isEqualTo(CleanupDecision.Transfer(toPrisonId = "MDI", dateLeft = LocalDate.parse("2026-07-02")))
    }

    @Test
    fun `left after the cut-off is too recent`() {
      val moved = prisoner(prisonId = "MDI", previousPrisonId = "LEI", previousPrisonLeavingDate = cutoff.plusDays(1))
      assertThat(rule.decide(moved, "LEI", cutoff)).isEqualTo(CleanupDecision.NotEligible(IneligibleReason.TOO_RECENT))
    }

    @Test
    fun `no usable date is never guessed at`() {
      val direct = prisoner(prisonId = "MDI", previousPrisonId = "LEI", previousPrisonLeavingDate = null, lastAdmissionDate = LocalDate.parse("2020-01-01"))
      assertThat(rule.decide(direct, "LEI", cutoff)).isEqualTo(CleanupDecision.NotEligible(IneligibleReason.NO_MOVEMENT_DATE))
      val indirect = prisoner(prisonId = "MDI", previousPrisonId = "BXI", lastAdmissionDate = null)
      assertThat(rule.decide(indirect, "LEI", cutoff)).isEqualTo(CleanupDecision.NotEligible(IneligibleReason.NO_MOVEMENT_DATE))
    }
  }
}
