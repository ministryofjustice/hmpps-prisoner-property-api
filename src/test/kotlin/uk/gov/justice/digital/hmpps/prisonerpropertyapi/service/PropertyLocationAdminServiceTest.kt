package uk.gov.justice.digital.hmpps.prisonerpropertyapi.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.client.LocationsClient
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.client.PropertyLocation
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.LocationContainerCount
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainerRepository
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.CreatePropertyLocationRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.UpdatePropertyLocationRequest
import java.util.UUID

class PropertyLocationAdminServiceTest {

  private val locationsClient = mock<LocationsClient>()
  private val repository = mock<PropertyContainerRepository>()
  private val service = PropertyLocationAdminService(locationsClient, repository)

  @Test
  fun `remove is rejected and does not call downstream when the location still holds containers`() {
    whenever(repository.countContainersInLocation(eq(LOCATION), anyOrNull())).thenReturn(2L)

    assertThatThrownBy { service.removePropertyLocation(LOCATION) }
      .isInstanceOf(PropertyLocationInUseException::class.java)

    verify(locationsClient, never()).removePropertyLocation(any())
  }

  @Test
  fun `remove drops the designation when the location is empty`() {
    whenever(repository.countContainersInLocation(eq(LOCATION), anyOrNull())).thenReturn(0L)
    whenever(locationsClient.removePropertyLocation(LOCATION)).thenReturn(location(LOCATION, capacity = 10))

    val result = service.removePropertyLocation(LOCATION)

    assertThat(result.id).isEqualTo(LOCATION)
    verify(locationsClient).removePropertyLocation(LOCATION)
  }

  @Test
  fun `create maps the new location with zero containers held`() {
    val request = CreatePropertyLocationRequest(localName = "Reception Store", capacity = 10)
    whenever(locationsClient.createPropertyLocation("LEI", request)).thenReturn(location(LOCATION, capacity = 10))

    val result = service.createPropertyLocation("LEI", request)

    assertThat(result.capacity).isEqualTo(10)
    assertThat(result.containersHeld).isEqualTo(0)
    assertThat(result.availableSpaces).isEqualTo(10)
  }

  @Test
  fun `update is rejected and does not call downstream when capacity is below the containers held`() {
    whenever(repository.countContainersInLocation(eq(LOCATION), anyOrNull())).thenReturn(3L)

    assertThatThrownBy { service.updatePropertyLocation(LOCATION, UpdatePropertyLocationRequest(capacity = 2)) }
      .isInstanceOf(PropertyLocationCapacityBelowUsageException::class.java)

    verify(locationsClient, never()).updatePropertyLocation(any(), any())
  }

  @Test
  fun `update allows capacity equal to the containers held`() {
    whenever(repository.countContainersInLocation(eq(LOCATION), anyOrNull())).thenReturn(3L)
    whenever(locationsClient.updatePropertyLocation(eq(LOCATION), any())).thenReturn(location(LOCATION, capacity = 3))

    val result = service.updatePropertyLocation(LOCATION, UpdatePropertyLocationRequest(capacity = 3))

    assertThat(result.capacity).isEqualTo(3)
    assertThat(result.containersHeld).isEqualTo(3)
    assertThat(result.availableSpaces).isEqualTo(0)
    verify(locationsClient).updatePropertyLocation(eq(LOCATION), any())
  }

  @Test
  fun `update with no capacity change is allowed regardless of containers held`() {
    whenever(repository.countContainersInLocation(eq(LOCATION), anyOrNull())).thenReturn(5L)
    whenever(locationsClient.updatePropertyLocation(eq(LOCATION), any())).thenReturn(location(LOCATION, capacity = 10))

    service.updatePropertyLocation(LOCATION, UpdatePropertyLocationRequest(localName = "Renamed Store"))

    verify(locationsClient).updatePropertyLocation(eq(LOCATION), any())
  }

  @Test
  fun `list annotates each location with how full it is`() {
    // the admin list must read live (uncached) so an admin sees their own writes immediately across pods
    whenever(locationsClient.getPropertyLocationsLive("LEI")).thenReturn(listOf(location(LOCATION, capacity = 10)))
    whenever(repository.countContainersByLocation("LEI")).thenReturn(listOf(count(LOCATION, 3)))

    val result = service.listPropertyLocations("LEI")

    assertThat(result).singleElement().satisfies({
      assertThat(it.capacity).isEqualTo(10)
      assertThat(it.containersHeld).isEqualTo(3)
      assertThat(it.availableSpaces).isEqualTo(7)
    })
  }

  private fun location(id: UUID, capacity: Int) = PropertyLocation(
    id = id,
    prisonId = "LEI",
    code = "PROP1",
    pathHierarchy = "PROP1",
    localName = "Reception Store",
    locationType = "BOX",
    capacity = capacity,
  )

  private fun count(id: UUID, count: Int) = object : LocationContainerCount {
    override val locationId = id
    override val count = count.toLong()
  }

  private companion object {
    private val LOCATION: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
  }
}
