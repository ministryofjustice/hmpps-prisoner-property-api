package uk.gov.justice.digital.hmpps.prisonerpropertyapi.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.data.domain.PageRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.client.LocationDetail
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.client.LocationsClient
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.LocationContainerCount
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainerRepository
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.BoxLocationSort
import java.util.UUID

class BoxLocationServiceTest {

  private val repository = mock<PropertyContainerRepository>()
  private val locationsClient = mock<LocationsClient>()
  private val service = BoxLocationService(repository, locationsClient)

  @BeforeEach
  fun stubBoxes() {
    whenever(locationsClient.getLocationsByType("LEI", "BOX")).thenReturn(
      listOf(box(BOX_A, "Box A"), box(BOX_B, "Box B"), box(BOX_C, "Box C")),
    )
  }

  @Test
  fun `annotates each box with its container count, including empty boxes`() {
    // The repository aggregate only returns boxes that hold containers (removed/offsite are excluded by the
    // query, which counts the denormalised current_internal_location_id) - empty boxes default to 0.
    whenever(repository.countContainersByLocation("LEI")).thenReturn(
      listOf(count(BOX_A, 2), count(BOX_B, 1)),
    )

    val result = service.getBoxLocations("LEI", BoxLocationSort.NAME).content.associateBy { it.id }

    assertThat(result[BOX_A]!!.containerCount).isEqualTo(2)
    assertThat(result[BOX_B]!!.containerCount).isEqualTo(1)
    assertThat(result[BOX_C]!!.containerCount).isEqualTo(0)
  }

  @Test
  fun `sorts alphabetically by name by default`() {
    whenever(repository.countContainersByLocation("LEI")).thenReturn(listOf(count(BOX_C, 2)))

    assertThat(service.getBoxLocations("LEI", BoxLocationSort.NAME).content.map { it.name })
      .containsExactly("Box A", "Box B", "Box C")
  }

  @Test
  fun `sorts emptiest first with a name tiebreak when requested`() {
    whenever(repository.countContainersByLocation("LEI")).thenReturn(
      listOf(count(BOX_A, 2), count(BOX_B, 1)),
    )

    val result = service.getBoxLocations("LEI", BoxLocationSort.FEWEST_CONTAINERS).content

    assertThat(result.map { it.name }).containsExactly("Box C", "Box B", "Box A")
    assertThat(result.map { it.containerCount }).containsExactly(0, 1, 2)
  }

  @Test
  fun `filters by a case-insensitive substring across code, name and path`() {
    whenever(repository.countContainersByLocation("LEI")).thenReturn(emptyList())

    // "box b" matches the local name "Box B" only
    assertThat(service.getBoxLocations("LEI", query = "box b").content.map { it.name }).containsExactly("Box B")
    // "recp-boxa" matches the path hierarchy of Box A only
    assertThat(service.getBoxLocations("LEI", query = "recp-boxa").content.map { it.name }).containsExactly("Box A")
    // "boxc" matches the code of Box C only
    assertThat(service.getBoxLocations("LEI", query = "boxc").content.map { it.name }).containsExactly("Box C")
  }

  @Test
  fun `supports star and question mark wildcards`() {
    whenever(repository.countContainersByLocation("LEI")).thenReturn(emptyList())

    // "*a" anchors nothing but ends with a - Box A code is "BoxA"
    assertThat(service.getBoxLocations("LEI", query = "box?").content.map { it.name })
      .containsExactly("Box A", "Box B", "Box C")
    assertThat(service.getBoxLocations("LEI", query = "recp*a").content.map { it.name }).containsExactly("Box A")
  }

  @Test
  fun `a blank query returns all boxes`() {
    whenever(repository.countContainersByLocation("LEI")).thenReturn(emptyList())

    assertThat(service.getBoxLocations("LEI", query = "   ").content).hasSize(3)
  }

  @Test
  fun `paginates the filtered, sorted results and reports the full total`() {
    whenever(repository.countContainersByLocation("LEI")).thenReturn(emptyList())

    val firstPage = service.getBoxLocations("LEI", pageable = PageRequest.of(0, 2))
    assertThat(firstPage.content.map { it.name }).containsExactly("Box A", "Box B")
    assertThat(firstPage.totalElements).isEqualTo(3)
    assertThat(firstPage.totalPages).isEqualTo(2)

    val secondPage = service.getBoxLocations("LEI", pageable = PageRequest.of(1, 2))
    assertThat(secondPage.content.map { it.name }).containsExactly("Box C")
    assertThat(secondPage.totalElements).isEqualTo(3)
  }

  private fun box(id: UUID, localName: String) = LocationDetail(
    id = id,
    prisonId = "LEI",
    code = localName.replace(" ", ""),
    pathHierarchy = "RECP-${localName.replace(" ", "")}",
    localName = localName,
    locationType = "BOX",
  )

  private fun count(id: UUID, count: Long) = object : LocationContainerCount {
    override val locationId = id
    override val count = count
  }

  private companion object {
    private val BOX_A: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val BOX_B: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val BOX_C: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")
  }
}
