package uk.gov.justice.digital.hmpps.prisonerpropertyapi

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class PrisonerPropertyApi

fun main(args: Array<String>) {
  runApplication<PrisonerPropertyApi>(*args)
}
