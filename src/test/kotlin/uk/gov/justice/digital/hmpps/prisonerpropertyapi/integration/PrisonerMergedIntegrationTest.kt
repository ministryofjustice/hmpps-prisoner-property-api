package uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import software.amazon.awssdk.services.sns.model.MessageAttributeValue
import software.amazon.awssdk.services.sns.model.PublishRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainerRepository
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.RemovalOutcome
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.CreatePropertyContainerRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.PropertyContainerDto
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.RemoveContainerRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.event.HmppsDomainEvent
import uk.gov.justice.hmpps.sqs.HmppsQueueService
import java.util.UUID

/**
 * A NOMIS prisoner-number merge. RETAINED is the number that survives; REMOVED is the one NOMIS deletes,
 * so everything filed under it has to move or it becomes unreachable.
 */
class PrisonerMergedIntegrationTest : IntegrationTestBase() {

  @Autowired
  private lateinit var repository: PropertyContainerRepository

  @Autowired
  private lateinit var hmppsQueueService: HmppsQueueService

  @Autowired
  private lateinit var objectMapper: ObjectMapper

  @AfterEach
  fun cleanUp() = repository.deleteAll()

  @Test
  fun `every container moves to the retained prisoner number`() {
    val first = createContainer(RETAINED, "SEAL1")
    val second = createContainer(REMOVED, "SEAL2")
    val third = createContainer(REMOVED, "SEAL3")

    publishPrisonerMerged()

    await untilAsserted {
      assertThat(repository.findByPrisonerNumber(RETAINED).map { it.id }).containsExactlyInAnyOrder(first.id, second.id, third.id)
    }
    assertThat(repository.findByPrisonerNumber(REMOVED)).isEmpty()
  }

  @Test
  fun `each moved container raises one update event naming the number it came from`() {
    val moved = createContainer(REMOVED, "SEAL1")

    publishPrisonerMerged()

    await untilAsserted {
      assertThat(repository.findById(moved.id).orElseThrow().prisonerNumber).isEqualTo(RETAINED)
    }
    assertThat(publishedEventsFor(moved.id).last()).satisfies({
      assertThat(it.eventType).isEqualTo("prison-property.container.updated")
      assertThat(it.prisonerNumber).isEqualTo(RETAINED)
      assertThat(it.changedFields).containsExactly("prisonerNumber")
      // The envelope carries the *new* owner, so the retired number has to ride along or a subscriber
      // holding data under the old key has nothing to re-key from.
      assertThat(it.additionalInformation?.get("removedNomsNumber")).isEqualTo(REMOVED)
    })
  }

  @Test
  fun `a removed container moves too - its history belongs to the person, not to active storage`() {
    val container = createContainer(REMOVED, "SEAL1")
    removeContainer(container.id)

    publishPrisonerMerged()

    await untilAsserted {
      assertThat(repository.findById(container.id).orElseThrow().prisonerNumber).isEqualTo(RETAINED)
    }
    assertThat(repository.findById(container.id).orElseThrow().removalOutcome).isEqualTo(RemovalOutcome.RETURNED)
  }

  @Test
  fun `a merge for a prisoner number holding no property changes nothing and publishes nothing`() {
    val untouched = createContainer(RETAINED, "SEAL1")
    val eventsBefore = publishedEventsFor(untouched.id).size

    publishPrisonerMerged()

    Thread.sleep(1000)
    assertThat(repository.findById(untouched.id).orElseThrow().prisonerNumber).isEqualTo(RETAINED)
    assertThat(publishedEventsFor(untouched.id)).hasSize(eventsBefore)
  }

  @Test
  fun `redelivering the same merge moves nothing more and publishes nothing more`() {
    val moved = createContainer(REMOVED, "SEAL1")

    publishPrisonerMerged()
    await untilAsserted {
      assertThat(repository.findById(moved.id).orElseThrow().prisonerNumber).isEqualTo(RETAINED)
    }
    val eventsAfterFirst = publishedEventsFor(moved.id).size

    publishPrisonerMerged()

    Thread.sleep(1000)
    assertThat(publishedEventsFor(moved.id)).hasSize(eventsAfterFirst)
    assertThat(repository.findByPrisonerNumber(RETAINED)).hasSize(1)
  }

  @Test
  fun `a merge of a prisoner number into itself is ignored`() {
    val container = createContainer(RETAINED, "SEAL1")
    val eventsBefore = publishedEventsFor(container.id).size

    publishPrisonerMerged(retained = RETAINED, removed = RETAINED)

    Thread.sleep(1000)
    assertThat(publishedEventsFor(container.id)).hasSize(eventsBefore)
  }

  private fun createContainer(prisonerNumber: String, sealNumber: String): PropertyContainerDto = webTestClient.post().uri("/property-containers")
    .headers(setAuthorisation(username = "A_USER", roles = listOf("ROLE_PRISONER_PROPERTY__RW")))
    .bodyValue(
      CreatePropertyContainerRequest(
        prisonerNumber = prisonerNumber,
        prisonId = "LEI",
        containerType = ContainerType.STANDARD,
        sealNumber = sealNumber,
        internalLocationId = null,
      ),
    )
    .exchange()
    .expectStatus().isCreated
    .expectBody(PropertyContainerDto::class.java)
    .returnResult().responseBody!!

  private fun removeContainer(id: UUID) {
    webTestClient.post().uri("/property-containers/$id/remove")
      .headers(setAuthorisation(username = "A_USER", roles = listOf("ROLE_PRISONER_PROPERTY__RW")))
      .bodyValue(RemoveContainerRequest(outcome = RemovalOutcome.RETURNED))
      .exchange()
      .expectStatus().isOk
  }

  private fun publishPrisonerMerged(retained: String = RETAINED, removed: String = REMOVED) {
    val topic = hmppsQueueService.findByTopicId("domainevents")!!
    val event = HmppsDomainEvent(
      eventType = "prison-offender-events.prisoner.merged",
      additionalInformation = mapOf("nomsNumber" to retained, "removedNomsNumber" to removed, "reason" to "MERGE"),
    )
    topic.snsClient.publish(
      PublishRequest.builder()
        .topicArn(topic.arn)
        .message(objectMapper.writeValueAsString(event))
        .messageAttributes(
          mapOf(
            "eventType" to MessageAttributeValue.builder().dataType("String").stringValue(event.eventType).build(),
          ),
        )
        .build(),
    ).get()
  }

  private companion object {
    private const val RETAINED = "A1234BC"
    private const val REMOVED = "A9999ZZ"
  }
}
