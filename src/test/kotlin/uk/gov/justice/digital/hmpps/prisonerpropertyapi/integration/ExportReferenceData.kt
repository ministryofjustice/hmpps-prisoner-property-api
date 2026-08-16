package uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerStatus
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEventType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.RemovalOutcome
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.StorageLocationType
import java.io.File

/**
 * Writes reference-data.csv, the companion to the SchemaSpy report and data-dictionary.csv.
 *
 * Every code in this schema is a JPA string enum resolved in Kotlin - there are no reference tables in
 * the database - so the schema report alone leaves an analyst looking at a varchar with no idea which
 * values are legal. This exports those lookups.
 *
 * Needs no database: the values come from the enums themselves, so the list cannot drift from the code.
 * A new enum value with no description fails the test rather than exporting a blank row.
 *
 * Excluded from normal test runs; run with `./gradlew -Pinit-db=true test` (see build.gradle.kts).
 */
class ExportReferenceData {

  @Test
  fun `exports reference data`() {
    val rows = mutableListOf<Row>()

    rows += enumRows(
      "property_container.container_type / property_event.container_type",
      ContainerType.entries,
      mapOf(
        ContainerType.STANDARD to "A standard sealed container of the prisoner's property.",
        ContainerType.EXCESS to "Property the prisoner is not allowed to keep in possession, held in storage.",
        ContainerType.VALUABLES to "Valuables, held separately from the prisoner's other property.",
        ContainerType.CONFISCATED to "Property taken from the prisoner and held rather than returned.",
      ),
    )

    rows += enumRows(
      "property_event.event_type",
      PropertyEventType.entries,
      mapOf(
        PropertyEventType.CREATED_SEALED to "The container was created and sealed. Carries the initial seal number.",
        PropertyEventType.SEAL_CHANGED to "The container was resealed. Carries the new seal number; the container id does not change.",
        PropertyEventType.CONTAINER_TYPE_CHANGE to "The container's type was changed.",
        PropertyEventType.MOVED to "The container was moved to another storage location - internal, or offsite to Branston.",
        PropertyEventType.PRISONER_RECEIVED to "The owner was received at another establishment, so the property is due to follow them.",
        PropertyEventType.PRISONER_RELEASED to "The owner was released, so the property is due to be returned to them.",
        PropertyEventType.DIED_IN_CUSTODY to "The owner died in custody, so the property is due to be returned.",
        PropertyEventType.TRANSFERRED to "The container was transferred out to another prison. This removes it from the sending prison; the receiving prison holds a separate record, reconciled via related_container_id.",
        PropertyEventType.RETURNED to "The container was returned to the prisoner.",
        PropertyEventType.DISPOSAL_REQUIRED to "Disposal was recorded as required. Note the DISPOSAL_REQUIRED status is derived by comparing proposed_disposal_date with today, not from this event.",
        PropertyEventType.DISPOSED to "The container was disposed of (destroyed).",
        PropertyEventType.COMBINED to "The container was combined into a new sealed container.",
        PropertyEventType.CREATED_IN_ERROR to "The container was recorded in error.",
        PropertyEventType.REMOVED to "Removed from the establishment with no recorded reason - what a NOMIS inactive container maps to, where it cannot be told whether it was returned, disposed or transferred.",
        PropertyEventType.REACTIVATED to "A removed container was brought back into active storage, reversing a REMOVED.",
      ),
      notes = { "status=${it.status}; carriesSeal=${it.carriesSeal}" },
    )

    rows += enumRows(
      "property_container.current_status",
      ContainerStatus.entries,
      mapOf(
        ContainerStatus.STORED to "In active storage.",
        ContainerStatus.DUE_FOR_TRANSFER_OUT to "Still held here, but the owner has moved, so it is due to follow them.",
        ContainerStatus.DUE_FOR_RETURN to "Due to be returned, because the owner has been released or died in custody.",
        ContainerStatus.DISPOSAL_REQUIRED to "The proposed disposal date has arrived and the container is still held. Derived from proposed_disposal_date at read time, so it is not stored in current_status.",
        ContainerStatus.DISPOSED to "Disposed of (destroyed), and out of active storage.",
        ContainerStatus.RETURNED to "Returned to the prisoner, and out of active storage.",
        ContainerStatus.TRANSFER to "Transferred out to another prison. Never shown for a live container - the sending prison's record is removed with outcome TRANSFERRED.",
        ContainerStatus.COMBINED to "Combined into another container, and out of active storage.",
        ContainerStatus.CREATED_IN_ERROR to "Recorded in error, and out of active storage.",
        ContainerStatus.REMOVED to "Removed with no recorded reason. Reversible - a REACTIVATED event returns it to STORED.",
      ),
    )

    rows += enumRows(
      "property_container.removal_outcome",
      RemovalOutcome.entries,
      mapOf(
        RemovalOutcome.DISPOSED to "Left active storage because it was disposed of.",
        RemovalOutcome.RETURNED to "Left active storage because it was returned to the prisoner.",
        RemovalOutcome.TRANSFERRED to "Left active storage because it was transferred to another prison.",
        RemovalOutcome.COMBINED to "Left active storage because it was combined into another container.",
        RemovalOutcome.CREATED_IN_ERROR to "Left active storage because it was recorded in error.",
        RemovalOutcome.REMOVED to "Left active storage for no recorded reason. The only reversible outcome.",
      ),
      notes = { "status=${it.status}; eventType=${it.eventType}" },
    )

    rows += enumRows(
      "property_container.current_storage_location_type / property_event.to_storage_location_type",
      StorageLocationType.entries,
      mapOf(
        StorageLocationType.INTERNAL to "A storage location within the prison. The location id is a hmpps-locations-inside-prison-api location UUID.",
        StorageLocationType.BRANSTON to "The offsite Branston warehouse, which has no internal location id.",
      ),
    )

    val output = File(System.getProperty("referenceDataOutput") ?: "reference-data.csv")
    output.bufferedWriter().use { writer ->
      writer.write("column_ref,code,description,notes\n")
      rows.forEach { writer.write("${it.toCsv()}\n") }
    }
    println("Wrote ${rows.size} reference data rows to ${output.absolutePath}")
  }

  /**
   * Every value of the enum, with its description. Fails rather than exporting a blank row when a value
   * has no description - a new enum value is exactly the thing a consumer would otherwise not be able to
   * decode.
   */
  private fun <T : Enum<T>> enumRows(
    columnRef: String,
    values: List<T>,
    descriptions: Map<T, String>,
    notes: (T) -> String = { "" },
  ): List<Row> {
    assertThat(values.filterNot(descriptions::containsKey))
      .describedAs("$columnRef values with no description - add one in ExportReferenceData")
      .isEmpty()

    return values.map { Row(columnRef, it.name, descriptions.getValue(it), notes(it)) }
  }

  private data class Row(
    val columnRef: String,
    val code: String,
    val description: String,
    val notes: String = "",
  ) {
    fun toCsv() = listOf(columnRef, code, description, notes).joinToString(",") { escape(it) }

    private fun escape(value: String) = "\"${value.replace("\"", "\"\"")}\""
  }
}
