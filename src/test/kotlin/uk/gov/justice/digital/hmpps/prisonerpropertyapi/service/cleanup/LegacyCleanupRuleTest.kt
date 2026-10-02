package uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.cleanup

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.client.Prisoner
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerType
import java.time.LocalDate

class LegacyCleanupRuleTest {

  private val rule = LegacyCleanupRule()
  private val today = LocalDate.parse("2026-10-02")
  private val cutoff = LegacyCleanupRule.cutoff(today)

  private fun prisoner(
    prisonId: String?,
    lastMovementTypeCode: String? = "ADM",
    lastMovementReasonCode: String? = null,
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
    lastMovementReasonCode = lastMovementReasonCode,
    lastMovementDate = lastMovementDate,
    previousPrisonId = previousPrisonId,
    previousPrisonLeavingDate = previousPrisonLeavingDate,
    lastAdmissionDate = lastAdmissionDate,
  )

  @Nested
  inner class Cutoff {
    @Test
    fun `is 13 months before today`() {
      assertThat(cutoff).isEqualTo(LocalDate.parse("2025-09-02"))
    }

    @Test
    fun `falls back to the end of a shorter month`() {
      assertThat(LegacyCleanupRule.cutoff(LocalDate.parse("2026-03-31"))).isEqualTo(LocalDate.parse("2025-02-28"))
    }
  }

  @Nested
  inner class Container {
    @Test
    fun `confiscated property is never closed, however old`() {
      assertThat(rule.containerExclusion(ContainerType.CONFISCATED, null, today)).isEqualTo(IneligibleReason.CONFISCATED)
      assertThat(rule.containerExclusion(ContainerType.CONFISCATED, LocalDate.parse("2020-01-01"), today)).isEqualTo(IneligibleReason.CONFISCATED)
    }

    @Test
    fun `property with a disposal date still to come is never closed`() {
      assertThat(rule.containerExclusion(ContainerType.STANDARD, today.plusDays(1), today)).isEqualTo(IneligibleReason.DISPOSAL_DATE_NOT_REACHED)
    }

    @Test
    fun `a disposal date that has arrived or passed does not protect the property`() {
      assertThat(rule.containerExclusion(ContainerType.STANDARD, today, today)).isNull()
      assertThat(rule.containerExclusion(ContainerType.STANDARD, LocalDate.parse("2024-01-01"), today)).isNull()
    }

    @ParameterizedTest
    @EnumSource(value = ContainerType::class, names = ["CONFISCATED"], mode = EnumSource.Mode.EXCLUDE)
    fun `any other type of property with no disposal date is eligible on its own account`(type: ContainerType) {
      assertThat(rule.containerExclusion(type, null, today)).isNull()
    }
  }

  @Test
  fun `an unresolved owner is never touched`() {
    assertThat(rule.decide(null, "LEI", cutoff)).isEqualTo(CleanupDecision.NotEligible(IneligibleReason.UNRESOLVED))
    assertThat(rule.decide(prisoner(prisonId = null), "LEI", cutoff)).isEqualTo(CleanupDecision.NotEligible(IneligibleReason.UNRESOLVED))
  }

  @Test
  fun `an owner still at the holding prison is never touched, however long ago they arrived`() {
    val here = prisoner(prisonId = "LEI", lastAdmissionDate = LocalDate.parse("2010-01-01"), confirmedReleaseDate = today.plusDays(1))
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
    fun `released exactly 13 months ago is removed, dated to the release`() {
      val released = prisoner(prisonId = "OUT", lastMovementTypeCode = "REL", lastMovementReasonCode = "CR", lastMovementDate = cutoff)
      assertThat(rule.decide(released, "LEI", cutoff)).isEqualTo(CleanupDecision.Remove(eventDate = cutoff, reason = CleanupReason.RELEASED))
    }

    @Test
    fun `released less than 13 months ago is too recent`() {
      val released = prisoner(prisonId = "OUT", lastMovementTypeCode = "REL", lastMovementDate = cutoff.plusDays(1))
      assertThat(rule.decide(released, "LEI", cutoff)).isEqualTo(CleanupDecision.NotEligible(IneligibleReason.TOO_RECENT))
    }

    @Test
    fun `a death in custody arrives as a release and is reported as a death`() {
      val died = prisoner(prisonId = "OUT", lastMovementTypeCode = "REL", lastMovementReasonCode = "DEC", lastMovementDate = LocalDate.parse("2024-05-01"))
      assertThat(rule.decide(died, "LEI", cutoff)).isEqualTo(CleanupDecision.Remove(eventDate = LocalDate.parse("2024-05-01"), reason = CleanupReason.DIED))
    }

    @ParameterizedTest
    @ValueSource(strings = ["ESCP", "UAL", "UAL_ECL"])
    fun `an escape or abscond arrives as a release and is reported as one`(reasonCode: String) {
      val escaped = prisoner(prisonId = "OUT", lastMovementTypeCode = "REL", lastMovementReasonCode = reasonCode, lastMovementDate = LocalDate.parse("2024-05-01"))
      assertThat(rule.decide(escaped, "LEI", cutoff)).isEqualTo(CleanupDecision.Remove(eventDate = LocalDate.parse("2024-05-01"), reason = CleanupReason.ESCAPED))
    }

    @Test
    fun `an escape less than 13 months ago is too recent`() {
      val escaped = prisoner(prisonId = "OUT", lastMovementTypeCode = "REL", lastMovementReasonCode = "UAL", lastMovementDate = cutoff.plusDays(1))
      assertThat(rule.decide(escaped, "LEI", cutoff)).isEqualTo(CleanupDecision.NotEligible(IneligibleReason.TOO_RECENT))
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
        previousPrisonLeavingDate = LocalDate.parse("2025-06-01"),
        lastAdmissionDate = LocalDate.parse("2025-06-02"),
      )
      assertThat(rule.decide(moved, "LEI", cutoff))
        .isEqualTo(CleanupDecision.Remove(eventDate = LocalDate.parse("2025-06-01"), reason = CleanupReason.TRANSFERRED, toPrisonId = "MDI"))
    }

    @Test
    fun `went somewhere else in between - the admission date to where they are now is the bound`() {
      val moved = prisoner(
        prisonId = "MDI",
        previousPrisonId = "BXI",
        previousPrisonLeavingDate = LocalDate.parse("2025-07-01"),
        lastAdmissionDate = LocalDate.parse("2025-07-02"),
      )
      assertThat(rule.decide(moved, "LEI", cutoff))
        .isEqualTo(CleanupDecision.Remove(eventDate = LocalDate.parse("2025-07-02"), reason = CleanupReason.TRANSFERRED, toPrisonId = "MDI"))
    }

    @Test
    fun `left less than 13 months ago is still awaiting transfer and too recent`() {
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
