package uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain

const val PRISON_ID_REGEX = "^[A-Z]{2}I|ZZGHI$"
const val PRISON_ID_MESSAGE = "Prison id must be 3 upper-case characters ending in an I, or ZZGHI"
const val AGENCY_ID_MESSAGE = "Agency id must be 3 upper-case characters ending in an I, or ZZGHI"
