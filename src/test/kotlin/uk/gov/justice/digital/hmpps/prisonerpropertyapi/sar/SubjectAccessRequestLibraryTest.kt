package uk.gov.justice.digital.hmpps.prisonerpropertyapi.sar

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.test.web.reactive.server.WebTestClient
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainer
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainerRepository
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEvent
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEventType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.RemovalOutcome
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.StorageLocationType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.subjectaccessrequest.SarApiDataTest
import uk.gov.justice.digital.hmpps.subjectaccessrequest.SarFlywaySchemaTest
import uk.gov.justice.digital.hmpps.subjectaccessrequest.SarIntegrationTestHelper
import uk.gov.justice.digital.hmpps.subjectaccessrequest.SarIntegrationTestHelperConfig
import uk.gov.justice.digital.hmpps.subjectaccessrequest.SarJpaEntitiesTest
import uk.gov.justice.digital.hmpps.subjectaccessrequest.SarReportTest
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import javax.sql.DataSource

/**
 * The four checks from the HMPPS SAR test library, against a fixed set of property fixtures.
 *
 * These are approval tests: each compares against a committed file under `src/test/resources/sar/`, and the
 * point of them is what happens when one fails.
 *
 *  - `SarApiDataTest` / `SarReportTest` fail when the response or the rendered report changes. The rendered
 *    HTML is not a throwaway fixture - it is the artefact the Offender SAR team review and sign off
 *    (MAPB-772), and no template change reaches preprod or prod without that sign-off. So a diff here is the
 *    trigger for a conversation with Branston, not something to regenerate and move on from.
 *  - `SarJpaEntitiesTest` fails when a column is added to any entity. That is the one with the most lasting
 *    value: it forces whoever adds a field to decide whether it belongs in a prisoner's report, rather than
 *    the field quietly never appearing.
 *  - `SarFlywaySchemaTest` fails when a migration is added, for the same reason from the schema side.
 *
 * Regenerate the files with [SarIntegrationTestHelper]'s `saveContentToFile`, then read them before
 * committing.
 */
@Import(SarIntegrationTestHelperConfig::class)
class SubjectAccessRequestLibraryTest :
  IntegrationTestBase(),
  SarApiDataTest,
  SarFlywaySchemaTest,
  SarJpaEntitiesTest,
  SarReportTest {

  @Autowired
  private lateinit var repository: PropertyContainerRepository

  @Autowired
  private lateinit var dataSource: DataSource

  @Autowired
  private lateinit var sarIntegrationTestHelper: SarIntegrationTestHelper

  @PersistenceContext
  private lateinit var entityManager: EntityManager

  override fun getDataSourceInstance(): DataSource = dataSource

  override fun getEntityManagerInstance(): EntityManager = entityManager

  override fun getSarHelper(): SarIntegrationTestHelper = sarIntegrationTestHelper

  override fun getWebTestClientInstance(): WebTestClient = webTestClient

  override fun getPrn(): String = PRISONER

  /**
   * Deserialise the response as plain JSON rather than into [SarPropertyContainer]. That is what the SAR
   * tool itself does - it fetches the JSON and renders the template against the parsed result - so the
   * template helpers see the strings they see in production. Rendering the typed objects instead would hand
   * `getLocationNameByDpsId` a UUID and `convertCamelCase` an enum, and pass or fail for reasons that have
   * nothing to do with the real report.
   */
  override fun getContentType(): Class<*> = List::class.java

  /**
   * The test library asks for "a complete representation of all possible data", because anything not
   * represented here is a shape of report nobody has ever looked at. Between them these three containers
   * exercise every field in the response and every helper the template calls: an internal move and an offsite
   * move to Branston, a reseal, a type change, a transfer out reconciled against another record, a return,
   * and a container waiting on disposal.
   *
   * Dates are fixed rather than relative so the approved files stay stable. The disposal date is in the past
   * for the same reason - the disposal-due status is derived by comparing it with today, so a future date
   * would change the expected report the moment it arrived.
   *
   * Idempotent, because the library calls this itself and the containers must not stack up.
   */
  override fun setupTestData() {
    repository.deleteAll()
    repository.saveAll(listOf(storedContainer(), transferredContainer(), returnedContainer()))
  }

  /** Created, resealed, moved within the prison, then moved offsite to Branston. Disposal now due. */
  private fun storedContainer(): PropertyContainer {
    val container = PropertyContainer(
      prisonerNumber = PRISONER,
      prisonId = "LEI",
      containerType = ContainerType.STANDARD,
      createdByUserId = "SAR_USER1",
      currentSealNumber = "SEAL0002",
      createDateTime = LocalDateTime.parse("2025-01-10T09:15:00"),
      proposedDisposalDate = LocalDate.parse("2025-11-01"),
    )
    container.events.add(
      PropertyEvent(
        container,
        PropertyEventType.CREATED_SEALED,
        LocalDateTime.parse("2025-01-10T09:15:00"),
        "SAR_USER1",
        sealNumber = "SEAL0001",
        toInternalLocationId = LOCATION_A,
        toStorageLocationType = StorageLocationType.INTERNAL,
      ),
    )
    container.events.add(
      PropertyEvent(container, PropertyEventType.SEAL_CHANGED, LocalDateTime.parse("2025-02-14T11:30:00"), "SAR_USER2", sealNumber = "SEAL0002"),
    )
    container.events.add(
      PropertyEvent(
        container,
        PropertyEventType.MOVED,
        LocalDateTime.parse("2025-03-20T14:05:00"),
        "SAR_USER1",
        fromInternalLocationId = LOCATION_A,
        toInternalLocationId = LOCATION_B,
        toStorageLocationType = StorageLocationType.INTERNAL,
      ),
    )
    container.events.add(
      PropertyEvent(
        container,
        PropertyEventType.MOVED,
        LocalDateTime.parse("2025-04-02T10:00:00"),
        "SAR_USER2",
        fromInternalLocationId = LOCATION_B,
        toStorageLocationType = StorageLocationType.BRANSTON,
      ),
    )
    container.events.add(
      PropertyEvent(
        container,
        PropertyEventType.DISPOSAL_REQUIRED,
        LocalDateTime.parse("2025-11-01T00:05:00"),
        "SAR_USER1",
        eventDate = LocalDate.parse("2025-11-01"),
      ),
    )
    container.refreshDerivedState()
    return container
  }

  /**
   * Created as EXCESS, retyped, then transferred out to Moorland when the prisoner moved - reconciled
   * against the record the receiving prison created, so it carries a related seal number.
   */
  private fun transferredContainer(): PropertyContainer {
    val container = PropertyContainer(
      prisonerNumber = PRISONER,
      prisonId = "LEI",
      containerType = ContainerType.VALUABLES,
      createdByUserId = "SAR_USER2",
      currentSealNumber = "SEAL0003",
      createDateTime = LocalDateTime.parse("2025-05-06T08:00:00"),
      removalOutcome = RemovalOutcome.TRANSFERRED,
      removalDate = LocalDate.parse("2025-07-18"),
    )
    container.events.add(
      PropertyEvent(
        container,
        PropertyEventType.CREATED_SEALED,
        LocalDateTime.parse("2025-05-06T08:00:00"),
        "SAR_USER2",
        sealNumber = "SEAL0003",
        toInternalLocationId = LOCATION_A,
        toStorageLocationType = StorageLocationType.INTERNAL,
        containerType = ContainerType.EXCESS,
      ),
    )
    container.events.add(
      PropertyEvent(container, PropertyEventType.CONTAINER_TYPE_CHANGE, LocalDateTime.parse("2025-06-01T13:45:00"), "SAR_USER1"),
    )
    container.events.add(
      PropertyEvent(
        container,
        PropertyEventType.PRISONER_RECEIVED,
        LocalDateTime.parse("2025-07-10T16:20:00"),
        "SAR_USER1",
        fromPrisonId = "LEI",
        toPrisonId = "MDI",
      ),
    )
    container.events.add(
      PropertyEvent(
        container,
        PropertyEventType.TRANSFERRED,
        LocalDateTime.parse("2025-07-18T09:30:00"),
        "SAR_USER2",
        fromPrisonId = "LEI",
        toPrisonId = "MDI",
        eventDate = LocalDate.parse("2025-07-18"),
        relatedContainerId = UUID.fromString("33333333-3333-3333-3333-333333333333"),
        relatedContainerSealNumber = "SEAL0099",
      ),
    )
    container.refreshDerivedState()
    return container
  }

  /** Handed back to the prisoner on release. */
  private fun returnedContainer(): PropertyContainer {
    val container = PropertyContainer(
      prisonerNumber = PRISONER,
      prisonId = "LEI",
      containerType = ContainerType.CONFISCATED,
      createdByUserId = "SAR_USER1",
      currentSealNumber = "SEAL0004",
      createDateTime = LocalDateTime.parse("2025-08-01T07:45:00"),
      removalOutcome = RemovalOutcome.RETURNED,
      removalDate = LocalDate.parse("2025-09-12"),
    )
    container.events.add(
      PropertyEvent(
        container,
        PropertyEventType.CREATED_SEALED,
        LocalDateTime.parse("2025-08-01T07:45:00"),
        "SAR_USER1",
        sealNumber = "SEAL0004",
        toInternalLocationId = LOCATION_B,
        toStorageLocationType = StorageLocationType.INTERNAL,
      ),
    )
    container.events.add(
      PropertyEvent(container, PropertyEventType.PRISONER_RELEASED, LocalDateTime.parse("2025-09-10T12:00:00"), "SAR_USER2"),
    )
    container.events.add(
      PropertyEvent(
        container,
        PropertyEventType.RETURNED,
        LocalDateTime.parse("2025-09-12T15:25:00"),
        "SAR_USER1",
        eventDate = LocalDate.parse("2025-09-12"),
      ),
    )
    container.refreshDerivedState()
    return container
  }

  private companion object {
    const val PRISONER = "A1234SR"
    val LOCATION_A: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    val LOCATION_B: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
  }
}
