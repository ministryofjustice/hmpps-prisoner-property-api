package uk.gov.justice.digital.hmpps.prisonerpropertyapi.event

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.entry
import org.junit.jupiter.api.Test
import java.util.UUID

class PropertyContainerEventFactoryTest {

  private val dpsId = UUID.fromString("11111111-1111-1111-1111-111111111111")

  @Test
  fun `syncEvent carries the NOMIS id and sync description`() {
    val event = PropertyContainerEventFactory.syncEvent(
      PropertyDomainEventType.CONTAINER_UPDATED,
      dpsId,
      nomisPropertyContainerId = 123,
      prisonerNumber = "A1234BC",
      changedFields = listOf("sealNumber"),
    )

    assertThat(event.eventType).isEqualTo("prison-property.container.updated")
    assertThat(event.description).isEqualTo("A prisoner property container was synchronised from NOMIS")
    assertThat(event.prisonerNumber).isEqualTo("A1234BC")
    assertThat(event.additionalInformation).containsExactly(
      entry("dpsId", dpsId.toString()),
      entry("nomisPropertyContainerId", 123L),
      entry("changedFields", listOf("sealNumber")),
    )
  }

  @Test
  fun `staffEvent omits the NOMIS id and uses the staff description`() {
    val event = PropertyContainerEventFactory.staffEvent(
      PropertyDomainEventType.CONTAINER_CREATED,
      dpsId,
      prisonerNumber = "A1234BC",
      changedFields = null,
    )

    assertThat(event.eventType).isEqualTo("prison-property.container.created")
    assertThat(event.description).isEqualTo("A prisoner property container was changed by a member of staff")
    assertThat(event.prisonerNumber).isEqualTo("A1234BC")
    assertThat(event.additionalInformation).containsExactly(
      entry("dpsId", dpsId.toString()),
    )
  }
}
