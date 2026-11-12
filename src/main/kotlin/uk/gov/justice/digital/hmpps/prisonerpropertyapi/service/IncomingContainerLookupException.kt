package uk.gov.justice.digital.hmpps.prisonerpropertyapi.service

class IncomingContainerNotFoundException : RuntimeException("No incoming property container matches the supplied prisoner, establishment and seal")

class AmbiguousIncomingContainerException : RuntimeException("Multiple incoming property containers match the supplied prisoner, establishment and seal")
