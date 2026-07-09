package uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain

import org.springframework.data.jpa.repository.JpaRepository

interface ActiveAgencyRepository : JpaRepository<ActiveAgency, String> {
  fun findAllByActiveTrue(): List<ActiveAgency>
}
